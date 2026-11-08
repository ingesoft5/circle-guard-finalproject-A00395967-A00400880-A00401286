#!/usr/bin/env bash
# Configura los 4 servidores de CircleGuard desde la máquina del administrador
# (después de provision.sh). Copia el código del repo a cada VM y ejecuta los
# bootstrap. Es idempotente: se puede ejecutar N veces.
#
#   ./infra/azure/bootstrap-all.sh            # tools y luego dev, stage y prod
#   ./infra/azure/bootstrap-all.sh dev prod   # solo esos ambientes
#
# El repo es privado: no se clona en los servidores (no se dejan credenciales
# de GitHub allí). Se envía la versión COMMITEADA con `git archive` por SSH.
# La contraseña de solo lectura de Nexus viaja de tools a cada ambiente por
# stdin, sin escribirse en disco ni en la salida.
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=$(cd "$HERE/../.." && pwd)
. "$HERE/../bootstrap/lib.sh"

RG=${RG:-circleguard-rg}
ADMIN_USER=${ADMIN_USER:-azureuser}
LOG_DIR=${LOG_DIR:-$REPO/docs/infra/evidencia}
ENVS=("$@"); ((${#ENVS[@]})) || ENVS=(tools dev stage prod)
SSH=(ssh -o StrictHostKeyChecking=accept-new -o ServerAliveInterval=30)

ip_of() { az network public-ip show -g "$RG" -n "cg-$1-ip" --query ipAddress -o tsv; }

sync_repo() {  # copia HEAD del repo a ~/circleguard en la VM
  git -C "$REPO" archive --format=tar HEAD | "${SSH[@]}" "$ADMIN_USER@$1" \
    'rm -rf ~/circleguard.new && mkdir -p ~/circleguard.new && tar -x -C ~/circleguard.new && rm -rf ~/circleguard && mv ~/circleguard.new ~/circleguard'
}

[[ -z $(git -C "$REPO" status --porcelain -- infra) ]] || warn "Hay cambios sin commit en infra/: se envía la versión commiteada"
TOOLS_IP=$(ip_of tools)
REGISTRY="$TOOLS_IP:8082"
mkdir -p "$LOG_DIR"

for env in "${ENVS[@]}"; do
  [[ $env == tools ]] || continue
  log "tools ($TOOLS_IP): copiando repo y ejecutando bootstrap-tools.sh"
  sync_repo "$TOOLS_IP"
  "${SSH[@]}" "$ADMIN_USER@$TOOLS_IP" \
    "cd ~/circleguard && sudo REGISTRY_HOST=$TOOLS_IP PUBLIC_HOST=$TOOLS_IP ./infra/bootstrap/bootstrap-tools.sh" \
    2>&1 | sed -E 's/(contraseña inicial: ).*/\1<redactado>/' | tee "$LOG_DIR/bootstrap-tools.log"
done

PULL_PW=""
pids=()
for env in "${ENVS[@]}"; do
  [[ $env == tools ]] && continue
  [[ $env =~ ^(dev|stage|prod)$ ]] || die "Ambiente inválido: $env"
  if [[ -z $PULL_PW ]]; then
    PULL_PW=$("${SSH[@]}" "$ADMIN_USER@$TOOLS_IP" "sudo grep '^NEXUS_PULL_PASSWORD=' /opt/cg-tools/.env | cut -d= -f2-")
    [[ -n $PULL_PW ]] || die "No encontré NEXUS_PULL_PASSWORD en tools (¿corrió bootstrap-tools.sh?)"
  fi
  host=$(ip_of "$env")
  log "$env ($host): copiando repo y ejecutando bootstrap-env.sh en segundo plano"
  (
    sync_repo "$host"
    printf '%s\n' "$PULL_PW" | "${SSH[@]}" "$ADMIN_USER@$host" \
      "read -r PW; cd ~/circleguard && sudo REGISTRY=$REGISTRY NEXUS_PULL_PASSWORD=\"\$PW\" ./infra/bootstrap/bootstrap-env.sh $env" \
      >"$LOG_DIR/bootstrap-env-$env.log" 2>&1
  ) &
  pids+=("$!:$env")
done

fail=0
for p in "${pids[@]}"; do
  if wait "${p%%:*}"; then ok "${p#*:} listo (log: $LOG_DIR/bootstrap-env-${p#*:}.log)"
  else warn "${p#*:} falló (log: $LOG_DIR/bootstrap-env-${p#*:}.log)"; fail=1; fi
done
((fail == 0)) || die "Algún ambiente falló"
ok "Servidores configurados"
