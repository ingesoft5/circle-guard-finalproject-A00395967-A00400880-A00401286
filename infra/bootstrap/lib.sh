#!/usr/bin/env bash
# Funciones comunes de los scripts bootstrap-*.sh (CG-31, CG-33).
#
# Todas son idempotentes: verifican el estado actual y solo cambian algo si
# hace falta. Cada una imprime "ya ..." / "sin cambios" cuando no hace nada,
# que es la evidencia de idempotencia de CG-34 (segunda ejecución).

log()  { printf '\e[1;34m[%s]\e[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
ok()   { printf '\e[1;32m[%s] ✓\e[0m %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf '\e[1;33m[%s] !\e[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die()  { printf '\e[1;31m[%s] ✗\e[0m %s\n' "$(date +%H:%M:%S)" "$*" >&2; exit 1; }

require_root() {
  [[ $EUID -eq 0 ]] || die "Ejecútalo con sudo: sudo $0 $*"
}

require_ubuntu() {
  [[ -r /etc/os-release ]] && . /etc/os-release
  [[ ${ID:-} == ubuntu ]] || warn "Probado en Ubuntu 22.04/24.04; este sistema es ${ID:-desconocido}."
}

# Instala paquetes apt solo si falta alguno.
ensure_packages() {
  local missing=()
  for p in "$@"; do dpkg -s "$p" &>/dev/null || missing+=("$p"); done
  if ((${#missing[@]} == 0)); then ok "Paquetes ya instalados: $*"; return; fi
  log "Instalando paquetes: ${missing[*]}"
  DEBIAN_FRONTEND=noninteractive apt-get update -qq
  DEBIAN_FRONTEND=noninteractive apt-get install -y -qq "${missing[@]}" >/dev/null
}

# Docker Engine + plugin compose desde el repositorio oficial de Docker.
ensure_docker() {
  if command -v docker &>/dev/null && docker compose version &>/dev/null; then
    ok "Docker ya instalado ($(docker --version | cut -d, -f1))"
  else
    log "Instalando Docker Engine y docker compose"
    ensure_packages ca-certificates curl gnupg
    install -m 0755 -d /etc/apt/keyrings
    [[ -f /etc/apt/keyrings/docker.asc ]] ||
      curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
    chmod a+r /etc/apt/keyrings/docker.asc
    . /etc/os-release
    echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
      > /etc/apt/sources.list.d/docker.list
    DEBIAN_FRONTEND=noninteractive apt-get update -qq
    DEBIAN_FRONTEND=noninteractive apt-get install -y -qq \
      docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null
  fi
  systemctl enable --now docker &>/dev/null
}

# Agrega un usuario a un grupo solo si no pertenece ya.
ensure_in_group() {
  local user=$1 group=$2
  if id -nG "$user" | tr ' ' '\n' | grep -qx "$group"; then
    ok "$user ya pertenece a $group"
  else
    log "Agregando $user al grupo $group"
    usermod -aG "$group" "$user"
  fi
}

# Registra un registro Docker HTTP en /etc/docker/daemon.json sin pisar el
# resto del archivo (usa jq para fusionar). Reinicia Docker solo si cambió.
# Decisión de la Parte 1: Nexus por HTTP dentro de la red privada, protegido
# por NSG y autenticación. En la Parte 2 se agrega TLS (E2.6).
ensure_insecure_registry() {
  local reg=$1 f=/etc/docker/daemon.json
  ensure_packages jq
  [[ -s $f ]] || echo '{}' > "$f"
  if jq -e --arg r "$reg" '(."insecure-registries" // []) | index($r)' "$f" >/dev/null; then
    ok "Registro $reg ya está en insecure-registries"
    return
  fi
  log "Agregando $reg a insecure-registries y reiniciando Docker"
  local tmp; tmp=$(mktemp)
  jq --arg r "$reg" '."insecure-registries" = (((."insecure-registries" // []) + [$r]) | unique)' "$f" > "$tmp"
  install -m 0644 "$tmp" "$f" && rm -f "$tmp"
  systemctl restart docker
}

# Escribe un archivo solo si el contenido cambió (devuelve 0 si lo cambió).
write_if_changed() {
  local dest=$1 mode=$2 tmp; tmp=$(mktemp)
  cat > "$tmp"
  if [[ -f $dest ]] && cmp -s "$tmp" "$dest"; then rm -f "$tmp"; return 1; fi
  install -m "$mode" "$tmp" "$dest" && rm -f "$tmp"
  return 0
}

# Regla de firewall del host (ufw). Repetir 'ufw allow' es seguro.
ensure_ufw_rule() {   # requiere ufw instalado (ensure_packages ufw)
  ufw allow "$@" >/dev/null
}

ensure_ufw_enabled() {
  if ufw status | grep -q '^Status: active'; then ok "ufw ya activo"
  else log "Activando ufw"; ufw --force enable >/dev/null; fi
}

# Valor aleatorio hexadecimal para secretos generados en el servidor.
random_hex() { openssl rand -hex "${1:-32}"; }
