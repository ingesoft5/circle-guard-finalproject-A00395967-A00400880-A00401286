#!/usr/bin/env bash
# Servidor de herramientas de CircleGuard con UN comando (CG-31, CG-32).
#
#   sudo ./infra/bootstrap/bootstrap-tools.sh
#
# En una VM Ubuntu limpia deja arriba Jenkins (:8080), SonarQube (:9000) y
# Nexus (:8081, registro Docker :8082), ya configurados. Es idempotente: se
# puede ejecutar N veces; la segunda vez solo informa "ya ..." y termina con 0.
#
# Variables opcionales:
#   PUBLIC_HOST   IP o DNS público de esta VM (para el webhook de Sonar y el resumen).
#                 Por defecto se consulta a ifconfig.me.
#   PRIVATE_IP    IP privada (la que usan los ambientes para el registro).
#                 Por defecto la primera de `hostname -I`.
#   BASE          Carpeta de estado fuera del repo (por defecto /opt/cg-tools).
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/lib.sh"
trap 'die "Falló en la línea $LINENO: $BASH_COMMAND"' ERR

require_root "$@"
require_ubuntu

BASE=${BASE:-/opt/cg-tools}
ENV_FILE=$BASE/.env
COMPOSE=(docker compose -p cg-tools -f "$HERE/tools/docker-compose.yml" --env-file "$ENV_FILE")
ADMIN_USER=${SUDO_USER:-ubuntu}

# --- 1. Paquetes y Docker ----------------------------------------------------
ensure_packages ca-certificates curl jq openssl git
ensure_docker
ensure_in_group "$ADMIN_USER" docker

PRIVATE_IP=${PRIVATE_IP:-$(hostname -I | awk '{print $1}')}
PUBLIC_HOST=${PUBLIC_HOST:-$(curl -fs --max-time 5 https://ifconfig.me || echo "$PRIVATE_IP")}
REGISTRY="$PRIVATE_IP:8082"

# --- 2. Kernel para SonarQube --------------------------------------------------
# SonarQube trae Elasticsearch, que necesita vm.max_map_count >= 262144; si no,
# muere al arrancar. Se escribe en /etc/sysctl.d para que sobreviva reinicios.
if printf 'vm.max_map_count=524288\nfs.file-max=131072\n' | write_if_changed /etc/sysctl.d/99-sonarqube.conf 0644; then
  log "Ajustando kernel para SonarQube (vm.max_map_count)"
  sysctl --system >/dev/null
else
  ok "Kernel ya ajustado para SonarQube"
fi

# --- 3. Registro HTTP de confianza ---------------------------------------------
# Jenkins usa el Docker del host (socket montado), así que es el daemon del
# host el que hace push a Nexus por la IP privada.
ensure_insecure_registry "$REGISTRY"

# --- 4. .env con secretos generados en el servidor (nunca en git) ----------------
install -d -m 700 "$BASE"
if [[ -f $ENV_FILE ]]; then
  ok ".env ya existe en $ENV_FILE (no se regeneran secretos)"
else
  log "Creando $ENV_FILE con secretos aleatorios"
  (umask 077
   while IFS= read -r line; do
     [[ $line == *=__GENERATE__ ]] && line="${line%%=*}=$(random_hex 24)"
     printf '%s\n' "$line"
   done < "$HERE/tools/.env.example" > "$ENV_FILE")
fi
docker_gid=$(getent group docker | cut -d: -f3)
if grep -qx "DOCKER_GID=$docker_gid" "$ENV_FILE"; then ok "DOCKER_GID ya es $docker_gid"
else sed -i "s/^DOCKER_GID=.*/DOCKER_GID=$docker_gid/" "$ENV_FILE"; log "DOCKER_GID=$docker_gid"; fi
chmod 600 "$ENV_FILE"
set -a; . "$ENV_FILE"; set +a

# --- 5. Levantar el stack -------------------------------------------------------
# 'up -d' converge al estado deseado: si ya está igual, no recrea nada.
log "Levantando Jenkins, SonarQube y Nexus (la primera vez tarda varios minutos)"
"${COMPOSE[@]}" up -d --build --wait --wait-timeout 600
ok "Contenedores healthy"

# --- 6. Configuración por API ---------------------------------------------------
NEXUS_CONTAINER=$("${COMPOSE[@]}" ps -q nexus | xargs docker inspect -f '{{.Name}}' | tr -d /) \
  "$HERE/tools/nexus-setup.sh"
JENKINS_URL="http://$PUBLIC_HOST:8080" TOKEN_FILE="$BASE/sonar-jenkins.token" \
  "$HERE/tools/sonar-setup.sh"

# Prueba del registro: login con el usuario de CI (no deja credenciales en root).
if echo "$NEXUS_CI_PASSWORD" | DOCKER_CONFIG=$(mktemp -d) docker login "$REGISTRY" -u "$NEXUS_CI_USER" --password-stdin >/dev/null 2>&1; then
  ok "docker login a $REGISTRY con $NEXUS_CI_USER funciona"
else
  warn "docker login a $REGISTRY falló; revisa el conector 8082 en Nexus"
fi

# --- 7. Firewall del host -------------------------------------------------------
# El perímetro real es el NSG de Azure (fuente restringida por puerto). ufw es
# una segunda capa. Ojo: los puertos publicados por Docker no pasan por ufw.
for p in 22/tcp 8080/tcp 9000/tcp 8081/tcp 8082/tcp; do ensure_ufw_rule "$p"; done
ensure_ufw_enabled

# --- Resumen ---------------------------------------------------------------------
jenkins_pw=$("${COMPOSE[@]}" exec -T jenkins cat /var/jenkins_home/secrets/initialAdminPassword 2>/dev/null || echo "(asistente ya completado)")
cat <<EOF

────────────────────────────────────────────────────────────────────────
 Servidor de herramientas listo
   Jenkins    http://$PUBLIC_HOST:8080   contraseña inicial: $jenkins_pw
   SonarQube  http://$PUBLIC_HOST:9000   admin / SONAR_ADMIN_PASSWORD
   Nexus      http://$PUBLIC_HOST:8081   admin / NEXUS_ADMIN_PASSWORD
   Registro   $REGISTRY  (CG_REGISTRY en Jenkins)

 Secretos (solo en este servidor, chmod 600):
   $ENV_FILE
   $BASE/sonar-jenkins.token   -> credencial 'sonar-token' de Jenkins
   NEXUS_CI_USER/PASSWORD      -> credencial 'nexus-docker' de Jenkins
   NEXUS_PULL_USER/PASSWORD    -> lo usa bootstrap-env.sh en cada ambiente

 Ver un secreto:  sudo grep NEXUS_CI_PASSWORD $ENV_FILE
────────────────────────────────────────────────────────────────────────
EOF
