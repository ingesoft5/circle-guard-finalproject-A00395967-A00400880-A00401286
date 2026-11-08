#!/usr/bin/env bash
# Configura SonarQube por la API web (CG-31). Lo llama bootstrap-tools.sh.
# Idempotente: cada paso consulta antes de crear.
#
#   1. Cambia la contraseña por defecto (admin/admin) por SONAR_ADMIN_PASSWORD.
#   2. Crea el quality gate "CircleGuard" y lo deja por defecto. Todo sobre
#      CÓDIGO NUEVO: cobertura >= 60 %, 0 problemas bloqueantes, 0 vulnerabilidades.
#      Define "código nuevo" como lo cambiado en los últimos 30 días.
#   3. Crea el webhook hacia Jenkins (sin él, waitForQualityGate se queda esperando).
#   4. Genera el token de análisis para Jenkins y lo guarda en
#      $TOKEN_FILE (chmod 600). Sonar solo lo muestra una vez.
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../lib.sh"

: "${SONAR_ADMIN_PASSWORD:?}" "${JENKINS_URL:?}" "${TOKEN_FILE:?}"
SONAR=${SONAR_URL:-http://localhost:9000}
GATE=CircleGuard

s() {  # s METHOD PATH [curl args...] (autenticado como admin)
  local m=$1 p=$2; shift 2
  curl -fsS -u "admin:$SONAR_ADMIN_PASSWORD" -X "$m" "$SONAR$p" "$@"
}

log "Esperando a que SonarQube esté UP"
for _ in $(seq 1 90); do
  curl -fs "$SONAR/api/system/status" | grep -q '"status":"UP"' && break
  sleep 5
done
curl -fs "$SONAR/api/system/status" | grep -q '"status":"UP"' || die "SonarQube no quedó UP"

# 1. Contraseña del admin
if curl -fs -u "admin:$SONAR_ADMIN_PASSWORD" "$SONAR/api/authentication/validate" | grep -q '"valid":true'; then
  ok "Contraseña del admin de SonarQube ya configurada"
else
  log "Cambiando la contraseña por defecto del admin de SonarQube"
  curl -fsS -u admin:admin -X POST "$SONAR/api/users/change_password" \
       --data-urlencode login=admin --data-urlencode previousPassword=admin \
       --data-urlencode "password=$SONAR_ADMIN_PASSWORD" ||
    die "No pude cambiar admin/admin. ¿Se cambió a mano? Pon esa contraseña en SONAR_ADMIN_PASSWORD."
fi

# 2. Quality gate: se CONVERGE a exactamente estas condiciones (métrica op umbral).
# SonarQube 10 crea los gates nuevos con las condiciones "Clean as You Code"
# (new_violations > 0, cobertura 80 %, duplicación 3 %, hotspots 100 %). La
# política del equipo es otra, así que se crean, ajustan o borran las necesarias.
# Las tres sobre código nuevo ("Clean as You Code"): el código heredado del
# proyecto base (p. ej. 2 bloqueantes S2229 en promotion) no bloquea, pero
# nada nuevo puede empeorarlo.
DESIRED=(
  "new_coverage LT 60"              # cobertura en código nuevo >= 60 %
  "new_blocker_violations GT 0"     # 0 problemas bloqueantes nuevos
  "new_vulnerabilities GT 0"        # 0 vulnerabilidades nuevas
)
if s GET "/api/qualitygates/show?name=$GATE" &>/dev/null; then
  ok "Quality gate '$GATE' ya existe"
else
  log "Creando quality gate '$GATE'"
  s POST /api/qualitygates/create --data-urlencode "name=$GATE" >/dev/null
fi
current=$(s GET "/api/qualitygates/show?name=$GATE" | jq -c '.conditions // []')
for d in "${DESIRED[@]}"; do
  read -r metric op error <<<"$d"
  existing=$(jq -c --arg m "$metric" 'map(select(.metric == $m)) | .[0] // empty' <<<"$current")
  if [[ -z $existing ]]; then
    log "Condición $metric $op $error"
    s POST /api/qualitygates/create_condition --data-urlencode "gateName=$GATE" \
      --data-urlencode "metric=$metric" --data-urlencode "op=$op" --data-urlencode "error=$error" >/dev/null
  elif [[ $(jq -r '.op + " " + .error' <<<"$existing") != "$op $error" ]]; then
    log "Ajustando condición $metric a $op $error"
    s POST /api/qualitygates/update_condition --data-urlencode "id=$(jq -r .id <<<"$existing")" \
      --data-urlencode "metric=$metric" --data-urlencode "op=$op" --data-urlencode "error=$error" >/dev/null
  else
    ok "Condición $metric $op $error ya está"
  fi
done
wanted=$(printf '%s\n' "${DESIRED[@]}" | awk '{print $1}')
for id_metric in $(jq -r '.[] | .id + ":" + .metric' <<<"$current"); do
  metric=${id_metric#*:}
  grep -qx "$metric" <<<"$wanted" && continue
  log "Quitando condición $metric (no es parte de la política del equipo)"
  s POST /api/qualitygates/delete_condition --data-urlencode "id=${id_metric%%:*}" >/dev/null
done
s POST /api/qualitygates/set_as_default --data-urlencode "name=$GATE" >/dev/null
ok "Quality gate por defecto: $GATE"

# Código nuevo = lo cambiado en los últimos 30 días (global). Con el valor por
# defecto, "versión anterior", y la versión de Gradle fija en 1.0.0-SNAPSHOT,
# Sonar nunca ve código nuevo y las tres condiciones de arriba no se evalúan.
# La línea base es el primer análisis dentro de la ventana, así que el código
# heredado (anterior al primer análisis) no cuenta como nuevo.
NEW_CODE_DAYS=30
if [[ $(s GET /api/new_code_periods/show | jq -r '.type + " " + (.value // "")') == "NUMBER_OF_DAYS $NEW_CODE_DAYS" ]]; then
  ok "Código nuevo: últimos $NEW_CODE_DAYS días"
else
  log "Definiendo código nuevo: últimos $NEW_CODE_DAYS días"
  s POST /api/new_code_periods/set --data-urlencode type=NUMBER_OF_DAYS --data-urlencode "value=$NEW_CODE_DAYS" >/dev/null
fi

# 3. Webhook hacia Jenkins
hook="$JENKINS_URL/sonarqube-webhook/"
if s GET /api/webhooks/list | jq -e --arg u "$hook" '.webhooks[] | select(.url==$u)' >/dev/null; then
  ok "Webhook a Jenkins ya existe"
else
  log "Creando webhook $hook"
  s POST /api/webhooks/create --data-urlencode name=jenkins --data-urlencode "url=$hook" >/dev/null
fi

# 4. Token para Jenkins
if [[ -s $TOKEN_FILE ]]; then
  ok "Token de Jenkins ya generado ($TOKEN_FILE)"
else
  log "Generando token de análisis para Jenkins"
  s POST /api/user_tokens/revoke --data-urlencode name=jenkins >/dev/null 2>&1 || true
  token=$(s POST /api/user_tokens/generate --data-urlencode name=jenkins --data-urlencode type=GLOBAL_ANALYSIS_TOKEN | jq -r .token)
  [[ -n $token && $token != null ]] || die "No se generó el token"
  (umask 077; printf '%s\n' "$token" > "$TOKEN_FILE")
fi

ok "SonarQube listo"
