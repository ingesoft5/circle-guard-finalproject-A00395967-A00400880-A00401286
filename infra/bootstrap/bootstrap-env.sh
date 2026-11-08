#!/usr/bin/env bash
# Prepara un servidor de ambiente de CircleGuard con UN comando (CG-33).
#
#   sudo REGISTRY=10.0.0.4:8082 NEXUS_PULL_PASSWORD=... ./infra/bootstrap/bootstrap-env.sh dev
#
# Deja lo que necesita el despliegue de Jenkins (cgDeploy: ssh deploy@host,
# cd /opt/circleguard/<env>, docker compose pull && up -d --wait):
#
#   /opt/circleguard/
#   ├── init-db.sql                       <- init-db.sql del repo
#   └── <env>/                            <- dueño: deploy
#       ├── docker-compose.yml            <- deploy/docker-compose.yml (Luis)
#       ├── docker-compose.override.yml   <- deploy/<env>/docker-compose.override.yml
#       └── .env                          <- secretos generados AQUÍ, chmod 600, nunca en git
#
# Usuario 'deploy': grupo docker, llave pública de Jenkins (credencial deploy-ssh)
# en authorized_keys y docker login de SOLO LECTURA contra Nexus.
#
# Idempotente: se puede correr N veces. También se puede correr para varios
# ambientes en la misma VM (dev, stage y prod quedan aislados por carpeta,
# proyecto de compose, red, volúmenes, .env y WEB_PORT).
#
# Variables:
#   REGISTRY              (obligatoria) IP privada de Nexus + :8082
#   NEXUS_PULL_USER       usuario de solo lectura (por defecto deploy-pull)
#   NEXUS_PULL_PASSWORD   obligatoria solo la primera vez (luego queda el login)
#   WEB_PORT              puerto del cliente web (por defecto 80)
#   JENKINS_PUBKEY        llave pública de Jenkins (por defecto la de deploy-ssh)
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO_ROOT=$(cd "$HERE/../.." && pwd)
. "$HERE/lib.sh"
trap 'die "Falló en la línea $LINENO: $BASH_COMMAND"' ERR

ENV_NAME=${1:-}
[[ $ENV_NAME =~ ^(dev|stage|prod)$ ]] || die "Uso: sudo $0 <dev|stage|prod>"
require_root "$@"
require_ubuntu

: "${REGISTRY:?Falta REGISTRY=<ip-privada-nexus>:8082}"
NEXUS_PULL_USER=${NEXUS_PULL_USER:-deploy-pull}
WEB_PORT=${WEB_PORT:-80}
DEPLOY_USER=deploy
ROOT_DIR=/opt/circleguard
APP_DIR=$ROOT_DIR/$ENV_NAME
# Llave pública de la credencial 'deploy-ssh' de Jenkins (la generó Simón).
# Es pública: sola no abre nada; la privada solo existe en Jenkins.
JENKINS_PUBKEY=${JENKINS_PUBKEY:-'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGSAPpuDBHRZl2gvVDzaIGfAT3vJqbJZg3jsU+357P1W jenkins-deploy@circleguard'}

log "Preparando ambiente '$ENV_NAME' en $(hostname)"

# --- 1. Paquetes, Docker y swap ---------------------------------------------------
ensure_packages ca-certificates curl jq openssl
ensure_docker

# Swap de 4 GB: colchón para los picos de arranque de las 8 JVM en VMs justas.
if swapon --show=NAME --noheadings | grep -q .; then
  ok "Swap ya configurada"
else
  log "Creando /swapfile de 4 GB"
  [[ -f /swapfile ]] || fallocate -l 4G /swapfile
  chmod 600 /swapfile; mkswap /swapfile >/dev/null; swapon /swapfile
  grep -qxF '/swapfile none swap sw 0 0' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi

mem_gb=$(awk '/MemTotal/ {printf "%d", $2/1024/1024 + 0.5}' /proc/meminfo)
(( mem_gb >= 8 )) || warn "Esta VM tiene ${mem_gb} GB; se recomiendan >= 8 GB por ambiente (o bajar JAVA_MEM_LIMIT en el .env)."

# --- 2. Usuario deploy ------------------------------------------------------------
if id "$DEPLOY_USER" &>/dev/null; then ok "Usuario $DEPLOY_USER ya existe"
else log "Creando usuario $DEPLOY_USER"; useradd -m -s /bin/bash "$DEPLOY_USER"; fi
ensure_in_group "$DEPLOY_USER" docker

DEPLOY_HOME=$(getent passwd "$DEPLOY_USER" | cut -d: -f6)
install -d -m 700 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$DEPLOY_HOME/.ssh"
AUTH=$DEPLOY_HOME/.ssh/authorized_keys
touch "$AUTH"
if grep -qxF "$JENKINS_PUBKEY" "$AUTH"; then ok "Llave de Jenkins ya autorizada"
else log "Autorizando la llave de Jenkins para $DEPLOY_USER"; echo "$JENKINS_PUBKEY" >> "$AUTH"; fi
chown "$DEPLOY_USER:$DEPLOY_USER" "$AUTH"; chmod 600 "$AUTH"

# --- 3. Registro Nexus (HTTP) y login de solo lectura ------------------------------
ensure_insecure_registry "$REGISTRY"

if sudo -u "$DEPLOY_USER" -H jq -e --arg r "$REGISTRY" '.auths[$r]' "$DEPLOY_HOME/.docker/config.json" &>/dev/null; then
  ok "$DEPLOY_USER ya tiene docker login en $REGISTRY"
else
  : "${NEXUS_PULL_PASSWORD:?Primera vez: falta NEXUS_PULL_PASSWORD (está en /opt/cg-tools/.env del servidor de herramientas)}"
  log "docker login de $DEPLOY_USER en $REGISTRY con $NEXUS_PULL_USER (solo lectura)"
  echo "$NEXUS_PULL_PASSWORD" | sudo -u "$DEPLOY_USER" -H docker login "$REGISTRY" -u "$NEXUS_PULL_USER" --password-stdin >/dev/null
fi

# --- 4. Carpetas y archivos de despliegue -------------------------------------------
install -d -m 755 "$ROOT_DIR"
install -d -m 750 -o "$DEPLOY_USER" -g "$DEPLOY_USER" "$APP_DIR"

sync_file() {  # origen destino dueño
  local src=$1 dst=$2 owner=$3
  if [[ ! -f $src ]]; then warn "No existe ${src#"$REPO_ROOT"/} (¿ya se fusionó el PR del compose de Luis?)"; return; fi
  if [[ -f $dst ]] && cmp -s "$src" "$dst"; then ok "${dst} ya está al día"; return; fi
  log "Copiando ${src#"$REPO_ROOT"/} -> $dst"
  install -m 644 -o "$owner" -g "$owner" "$src" "$dst"
}
sync_file "$REPO_ROOT/init-db.sql" "$ROOT_DIR/init-db.sql" root
sync_file "$REPO_ROOT/deploy/docker-compose.yml" "$APP_DIR/docker-compose.yml" "$DEPLOY_USER"
sync_file "$REPO_ROOT/deploy/$ENV_NAME/docker-compose.override.yml" "$APP_DIR/docker-compose.override.yml" "$DEPLOY_USER"

# --- 5. .env del ambiente (secretos generados aquí, solo la primera vez) -----------
ENV_FILE=$APP_DIR/.env
SECRETS=(POSTGRES_PASSWORD NEO4J_PASSWORD LDAP_ADMIN_PASSWORD JWT_SECRET QR_SECRET VAULT_SECRET VAULT_SALT)

set_var() {  # set_var CLAVE VALOR: reemplaza o agrega en el .env
  if grep -q "^$1=" "$ENV_FILE"; then sed -i "s|^$1=.*|$1=$2|" "$ENV_FILE"
  else printf '%s=%s\n' "$1" "$2" >> "$ENV_FILE"; fi
}

if [[ -f $ENV_FILE ]]; then
  ok ".env de $ENV_NAME ya existe (no se regeneran secretos)"
else
  log "Creando $ENV_FILE con secretos aleatorios"
  example=$REPO_ROOT/deploy/$ENV_NAME/.env.example
  (umask 077; if [[ -f $example ]]; then cp "$example" "$ENV_FILE"; else : > "$ENV_FILE"; fi)
  for k in "${SECRETS[@]}"; do set_var "$k" "$(random_hex 32)"; done   # VAULT_SALT queda hexadecimal
fi
# Variables que dependen de la infraestructura: se reafirman en cada ejecución.
for kv in "CG_ENV=$ENV_NAME" "REGISTRY=$REGISTRY" "WEB_PORT=$WEB_PORT"; do
  k=${kv%%=*}; v=${kv#*=}
  grep -qx "$kv" "$ENV_FILE" && continue
  log "$k=$v"; set_var "$k" "$v"
done
for k in "${SECRETS[@]}"; do
  grep -Eq "^$k=.+" "$ENV_FILE" || { log "Generando $k faltante"; set_var "$k" "$(random_hex 32)"; }
done
chown "$DEPLOY_USER:$DEPLOY_USER" "$ENV_FILE"; chmod 600 "$ENV_FILE"

# --- 6. Firewall del host -----------------------------------------------------------
# El NSG de Azure limita el 22 a Jenkins + equipo; aquí solo se abren los puertos.
ensure_packages ufw
ensure_ufw_rule 22/tcp
ensure_ufw_rule "$WEB_PORT/tcp"
ensure_ufw_enabled

# --- 7. Verificación ------------------------------------------------------------------
sudo -u "$DEPLOY_USER" -H docker ps >/dev/null && ok "$DEPLOY_USER puede usar Docker"
if [[ -f $APP_DIR/docker-compose.yml ]]; then
  (cd "$APP_DIR" && sudo -u "$DEPLOY_USER" -H env VERSION=v0.0.0 docker compose config -q) &&
    ok "docker compose config válido en $APP_DIR"
fi

cat <<EOF

────────────────────────────────────────────────────────────────────────
 Ambiente $ENV_NAME listo en $APP_DIR
   Jenkins entra con:  ssh deploy@<ip-de-esta-vm>
   Cliente web:        http://<ip-de-esta-vm>:$WEB_PORT
   Registro:           $REGISTRY (login de solo lectura: $NEXUS_PULL_USER)
────────────────────────────────────────────────────────────────────────
EOF
