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
#   6. Limpieza de SNAPSHOT (CG-117): política que borra los tags
#      vX.Y.Z-SNAPSHOT.N con más de SNAPSHOT_RETENTION_DAYS días (7 por defecto),
#      la recolección de basura de Docker y la compactación del blob store. Las
#      releases vX.Y.Z nunca coinciden con la expresión, así que no se borran.
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

# 6. Limpieza de SNAPSHOT (CG-117)
# Nexus OSS no publica la API de políticas y tareas en /v1 (es de la versión Pro):
# se usa la API interna que usa la propia UI, la misma de la versión 3.70.1.
# Convención (docs/change-management/versionado-y-releases.md):
#   vX.Y.Z-SNAPSHOT.N  dev y stage, se borra a los N días
#   vX.Y.Z             prod, nunca se borra (no coincide con la expresión)
RETENTION=${SNAPSHOT_RETENTION_DAYS:-7}
POLICY=circleguard-snapshots
# Nexus compara contra la ruta completa del asset (con o sin "/" inicial según la versión).
SNAPSHOT_REGEX="/?v2/$REPO/[^/]+/manifests/v[0-9]+\\.[0-9]+\\.[0-9]+-SNAPSHOT\\.[0-9]+"
CSRF=$(openssl rand -hex 16)
INTERNAL_OUT=$(mktemp)   # se crea aquí: internal corre dentro de $(...), en un subshell
internal() {  # internal METHOD PATH [JSON] -> imprime el código HTTP (cuerpo en $INTERNAL_OUT)
  curl -s -o "$INTERNAL_OUT" -w '%{http_code}' -u "admin:$NEXUS_ADMIN_PASSWORD" -X "$1" \
       -H 'X-Nexus-UI: true' -H "NX-ANTI-CSRF-TOKEN: $CSRF" -b "NX-ANTI-CSRF-TOKEN=$CSRF" \
       -H 'Content-Type: application/json' ${3:+-d "$3"} "$NEXUS/service/rest/internal$2"
}
policy=$(jq -n --arg n "$POLICY" --arg r "$SNAPSHOT_REGEX" --argjson d "$RETENTION" '{
  name: $n, format: "docker", criteriaLastBlobUpdated: $d, criteriaAssetRegex: $r,
  notes: "CG-117: borra los tags vX.Y.Z-SNAPSHOT.N con más de \($d) días. Las releases vX.Y.Z no coinciden."}')
if [[ $(internal GET "/cleanup-policies/$POLICY") == 200 ]]; then
  if jq -e --argjson want "$policy" '.criteriaLastBlobUpdated == $want.criteriaLastBlobUpdated and .criteriaAssetRegex == $want.criteriaAssetRegex' "$INTERNAL_OUT" >/dev/null; then
    ok "Política de limpieza $POLICY ya existe ($RETENTION días)"
  else
    log "Actualizando política $POLICY ($RETENTION días)"
    [[ $(internal PUT "/cleanup-policies/$POLICY" "$policy") == 20[04] ]] || die "Política $POLICY: $(cat "$INTERNAL_OUT")"
  fi
else
  log "Creando política de limpieza $POLICY ($RETENTION días)"
  [[ $(internal POST /cleanup-policies "$policy") == 200 ]] || die "Política $POLICY: $(cat "$INTERNAL_OUT")"
fi
rm -f "$INTERNAL_OUT"

# La política solo actúa sobre los repos que la tienen asociada.
repo_json=$(curl -fs -u "admin:$NEXUS_ADMIN_PASSWORD" "$API/repositories/docker/hosted/$REPO")
if jq -e --arg p "$POLICY" '(.cleanup.policyNames // []) | index($p)' <<<"$repo_json" >/dev/null; then
  ok "Repo '$REPO' ya usa la política $POLICY"
else
  log "Asociando la política $POLICY al repo '$REPO'"
  body=$(jq --arg p "$POLICY" '{name, online, storage, docker, component, cleanup: {policyNames: [$p]}}' <<<"$repo_json")
  code=$(api PUT "/repositories/docker/hosted/$REPO" "$body")
  [[ $code == 204 ]] || die "Asociar política: HTTP $code"
fi

# Tareas programadas (hora del servidor, UTC). Orden: la tarea "Cleanup service"
# que trae Nexus (01:00) borra los tags; la GC de Docker (02:00) quita las capas
# que ya nadie usa y la compactación (03:00) libera el disco.
# Las tareas solo se crean con ExtDirect (la API de la UI), con sesión y token CSRF.
NX_COOKIES=$(mktemp)
curl -fs -c "$NX_COOKIES" -o /dev/null -X POST -H 'X-Nexus-UI: true' \
  --data-urlencode "username=$(printf admin | base64)" \
  --data-urlencode "password=$(printf %s "$NEXUS_ADMIN_PASSWORD" | base64 | tr -d '\n')" \
  "$NEXUS/service/rapture/session" || die "No se pudo abrir sesión en la UI de Nexus"
NX_SESSION=$(awk '$6=="NXSESSIONID"{print $7}' "$NX_COOKIES"); rm -f "$NX_COOKIES"
extdirect() {  # extdirect METHOD DATA_JSON -> imprime la respuesta
  curl -fs -X POST "$NEXUS/service/extdirect" -H 'X-Nexus-UI: true' -H 'Content-Type: application/json' \
       -H "Cookie: NXSESSIONID=$NX_SESSION; NX-ANTI-CSRF-TOKEN=$CSRF" -H "NX-ANTI-CSRF-TOKEN: $CSRF" \
       -d "$(jq -n --arg m "$1" --argjson d "$2" '{action: "coreui_Task", method: $m, data: $d, type: "rpc", tid: 1}')"
}
existing_tasks=$(extdirect read null | jq -r '.result.data[].name')
ensure_task() {  # ensure_task NOMBRE TIPO CRON PROPIEDADES(json)
  if grep -qxF "$1" <<<"$existing_tasks"; then ok "Tarea '$1' ya existe"; return; fi
  log "Creando tarea '$1' ($3)"
  extdirect create "$(jq -n --arg n "$1" --arg t "$2" --arg c "$3" --argjson p "$4" '[{
    enabled: true, name: $n, typeId: $t, notificationCondition: "FAILURE",
    schedule: "advanced", cronExpression: $c, timeZoneOffset: "+00:00", properties: $p}]')" |
    jq -e '.result.success' >/dev/null || die "No se pudo crear la tarea '$1'"
}
ensure_task "CircleGuard - Docker GC" repository.docker.gc "0 0 2 * * ?" "{\"repositoryName\":\"$REPO\",\"deployOffset\":\"24\"}"
ensure_task "CircleGuard - Compactar blob store" blobstore.compact "0 0 3 * * ?" '{"blobstoreName":"default"}'

ok "Nexus listo: registro Docker en :8082 (repo $REPO)"
