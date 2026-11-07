#!/usr/bin/env bash
# Configura Nexus como registro Docker de CircleGuard por la API REST (CG-32).
# Lo llama bootstrap-tools.sh; es idempotente (cada paso consulta antes de crear).
#
#   1. Cambia la contraseña inicial del admin por NEXUS_ADMIN_PASSWORD.
#   2. Activa el "Docker Bearer Token Realm" (sin él, docker login falla).
#   3. Desactiva el acceso anónimo.
#   4. Crea el repo docker (hosted) "circleguard", conector HTTP 8082 y
#      writePolicy ALLOW_ONCE: un tag publicado no se puede sobrescribir
#      (v1.0.0 siempre es la misma imagen = inmutabilidad en el registro).
#   5. Crea dos roles y usuarios con mínimo privilegio:
#        jenkins-ci  -> push/pull solo en "circleguard"
#        deploy-pull -> solo lectura en "circleguard" (servidores de ambiente)
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../lib.sh"

: "${NEXUS_ADMIN_PASSWORD:?}" "${NEXUS_CI_USER:?}" "${NEXUS_CI_PASSWORD:?}" "${NEXUS_PULL_USER:?}" "${NEXUS_PULL_PASSWORD:?}"
NEXUS=${NEXUS_URL:-http://localhost:8081}
API=$NEXUS/service/rest/v1
REPO=circleguard
CONTAINER=${NEXUS_CONTAINER:-cg-tools-nexus-1}

api() {  # api METHOD PATH [JSON] -> imprime el código HTTP
  local m=$1 p=$2 body=${3:-}
  curl -s -o /dev/null -w '%{http_code}' -u "admin:$NEXUS_ADMIN_PASSWORD" -X "$m" \
       -H 'Content-Type: application/json' ${body:+-d "$body"} "$API$p"
}

log "Esperando a que Nexus responda"
for _ in $(seq 1 60); do
  curl -fs "$API/status/writable" >/dev/null && break
  sleep 5
done
curl -fs "$API/status/writable" >/dev/null || die "Nexus no respondió en 5 minutos"

# 1. Contraseña del admin
if [[ $(curl -s -o /dev/null -w '%{http_code}' -u "admin:$NEXUS_ADMIN_PASSWORD" "$API/status/check") == 200 ]]; then
  ok "Contraseña del admin de Nexus ya configurada"
else
  initial=$(docker exec "$CONTAINER" cat /nexus-data/admin.password 2>/dev/null) ||
    die "No pude leer la contraseña inicial y NEXUS_ADMIN_PASSWORD no funciona. ¿Se cambió a mano?"
  log "Cambiando la contraseña inicial del admin de Nexus"
  curl -fs -u "admin:$initial" -X PUT -H 'Content-Type: text/plain' \
       --data "$NEXUS_ADMIN_PASSWORD" "$API/security/users/admin/change-password"
  docker exec "$CONTAINER" rm -f /nexus-data/admin.password
fi

# 2. Realms (PUT reemplaza la lista: repetirlo da el mismo estado)
code=$(api PUT /security/realms/active '["NexusAuthenticatingRealm","DockerToken"]')
[[ $code == 204 ]] && ok "Realms activos: NexusAuthenticatingRealm, DockerToken" || die "Realms: HTTP $code"

# 3. Sin acceso anónimo
code=$(api PUT /security/anonymous '{"enabled":false,"userId":"anonymous","realmName":"NexusAuthorizingRealm"}')
[[ $code == 200 ]] && ok "Acceso anónimo desactivado" || warn "Acceso anónimo: HTTP $code"

# 4. Repositorio Docker
if [[ $(api GET "/repositories/$REPO") == 200 ]]; then
  ok "Repo Docker '$REPO' ya existe"
else
  log "Creando repo Docker hosted '$REPO' (HTTP 8082, ALLOW_ONCE)"
  code=$(api POST /repositories/docker/hosted "{
    \"name\": \"$REPO\", \"online\": true,
    \"storage\": {\"blobStoreName\": \"default\", \"strictContentTypeValidation\": true, \"writePolicy\": \"ALLOW_ONCE\"},
    \"docker\": {\"v1Enabled\": false, \"forceBasicAuth\": true, \"httpPort\": 8082}
  }")
  [[ $code == 201 ]] || die "Crear repo: HTTP $code"
fi

# 5. Roles y usuarios
ensure_role() {  # id descripción privilegios(json)
  if [[ $(api GET "/security/roles/$1") == 200 ]]; then ok "Rol $1 ya existe"; return; fi
  log "Creando rol $1"
  code=$(api POST /security/roles "{\"id\":\"$1\",\"name\":\"$1\",\"description\":\"$2\",\"privileges\":$3,\"roles\":[]}")
  [[ $code == 200 ]] || die "Rol $1: HTTP $code"
}
P=nx-repository-view-docker-$REPO
ensure_role cg-docker-push "Jenkins: push y pull en $REPO" "[\"$P-add\",\"$P-edit\",\"$P-read\",\"$P-browse\"]"
ensure_role cg-docker-pull "Ambientes: solo pull de $REPO" "[\"$P-read\",\"$P-browse\"]"

ensure_user() {  # usuario contraseña rol
  local found
  found=$(curl -fs -u "admin:$NEXUS_ADMIN_PASSWORD" "$API/security/users?userId=$1" | jq --arg u "$1" 'map(select(.userId==$u)) | length')
  if [[ $found == 1 ]]; then ok "Usuario $1 ya existe"; return; fi
  log "Creando usuario $1 con rol $3"
  code=$(api POST /security/users "{\"userId\":\"$1\",\"firstName\":\"$1\",\"lastName\":\"CircleGuard\",
    \"emailAddress\":\"$1@circleguard.local\",\"password\":\"$2\",\"status\":\"active\",\"roles\":[\"$3\"]}")
  [[ $code == 200 ]] || die "Usuario $1: HTTP $code"
}
ensure_user "$NEXUS_CI_USER" "$NEXUS_CI_PASSWORD" cg-docker-push
ensure_user "$NEXUS_PULL_USER" "$NEXUS_PULL_PASSWORD" cg-docker-pull

ok "Nexus listo: registro Docker en :8082 (repo $REPO)"
