#!/usr/bin/env bash
# Crea la infraestructura de CircleGuard en Azure con UN comando (CG-30, CG-31, CG-33).
#
#   ./infra/azure/provision.sh            # crea o converge (idempotente)
#   ./infra/azure/provision.sh --status   # solo muestra IPs y estado
#
# Requisitos: az CLI con sesión iniciada (az login) y una llave SSH pública.
#
# Topología (decisión en docs/infra/topologia.md). La suscripción Azure for
# Students limita a 6 vCPU por región, así que se reparte en dos regiones:
#
#   westus2       cg-tools  B4s_v2  4 vCPU / 16 GB   Jenkins, SonarQube, Nexus
#                 cg-dev    B2s_v2  2 vCPU /  8 GB   ambiente dev
#   chilecentral  cg-stage  B2s_v2  2 vCPU /  8 GB   ambiente stage
#                 cg-prod   B2s_v2  2 vCPU /  8 GB   ambiente prod
#
# Tamaños con respaldo: Azure rechaza a veces un tamaño por falta de capacidad
# en la región (SkuNotAvailable, "Capacity Restrictions"), aunque haya cuota. El
# 7 de noviembre, para esta suscripción, solo la familia Bsv2 tenía capacidad
# (ver probe-capacity.sh y docs/infra/topologia.md). Cada VM tiene una lista de
# tamaños equivalentes en orden de preferencia y se usa el primero que Azure acepte.
#
# Mínimo privilegio en la red (NSG por VM):
#   tools  22         <- solo ADMIN_CIDR (equipo)
#          8080       <- Internet (webhook de GitHub hacia Jenkins)
#          9000, 8081 <- solo ADMIN_CIDR (UI de Sonar y Nexus)
#          8082       <- solo las IPs públicas de dev, stage, prod y tools (registro)
#   envs   22         <- solo la IP de tools (Jenkins) y ADMIN_CIDR
#          80         <- Internet (cliente web: E2E desde Jenkins y demo)
#
# Idempotente: cada recurso se consulta antes de crearlo; la segunda ejecución
# solo informa "ya existe".
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../bootstrap/lib.sh"
trap 'die "Falló en la línea $LINENO: $BASH_COMMAND"' ERR

RG=${RG:-circleguard-rg}
RG_LOCATION=${RG_LOCATION:-westus2}
ADMIN_USER=${ADMIN_USER:-azureuser}
SSH_PUBKEY=${SSH_PUBKEY:-$HOME/.ssh/id_ed25519.pub}
ADMIN_CIDR=${ADMIN_CIDR:-$(curl -fs --max-time 5 https://ifconfig.me)/32}
IMAGE=${IMAGE:-Canonical:0001-com-ubuntu-server-jammy:22_04-lts-gen2:latest}
TAGS=(project=circleguard course=ingesoft5)

# nombre:región:tamaños (en orden de preferencia, separados por |):disco_GB
TOOLS_SIZES="Standard_B4s_v2|Standard_B4ms|Standard_D4s_v3|Standard_E2s_v3"   # 16 GB
ENV_SIZES="Standard_B2s_v2|Standard_B2ms|Standard_D2s_v3"                     #  8 GB
VMS=(
  "cg-tools:westus2:$TOOLS_SIZES:64"
  "cg-dev:westus2:$ENV_SIZES:40"
  "cg-stage:chilecentral:$ENV_SIZES:40"
  "cg-prod:chilecentral:$ENV_SIZES:40"
)

field() { local IFS=:; read -r -a f <<<"$1"; echo "${f[$2]}"; }
exists() { "$@" --only-show-errors -o none &>/dev/null; }
# Un recurso con el nombre esperado pero en otra región no se puede "convergir":
# hay que borrarlo a mano (evita adjuntar a una VM una IP de otra región).
same_region() {  # same_region TIPO NOMBRE REGIÓN
  local loc; loc=$(az resource show -g "$RG" -n "$2" --resource-type "$1" --query location -o tsv)
  [[ $loc == "$3" ]] || die "$2 existe en $loc pero se espera en $3. Bórralo y vuelve a ejecutar."
}

status() {
  az vm list -g "$RG" -d --query "[].{vm:name, region:location, size:hardwareProfile.vmSize, power:powerState, publicIp:publicIps, privateIp:privateIps}" -o table
}
[[ ${1:-} == --status ]] && { status; exit 0; }

command -v az >/dev/null || die "Falta az CLI"
az account show -o none 2>/dev/null || die "Inicia sesión con: az login"
[[ -f $SSH_PUBKEY ]] || die "No existe la llave pública $SSH_PUBKEY"
[[ $ADMIN_CIDR =~ ^[0-9.]+/[0-9]+$ ]] || die "ADMIN_CIDR inválido: '$ADMIN_CIDR'"
log "Suscripción: $(az account show --query name -o tsv) · acceso de administración desde $ADMIN_CIDR"

# --- 1. Grupo de recursos ----------------------------------------------------------
if exists az group show -n "$RG"; then ok "Grupo $RG ya existe"
else log "Creando grupo $RG"; az group create -n "$RG" -l "$RG_LOCATION" --tags "${TAGS[@]}" -o none; fi

# --- 2. Red por región (VNet + subred) ----------------------------------------------
REGIONS_USED=$(for vm in "${VMS[@]}"; do field "$vm" 1; done | sort -u)
n=10
for region in $REGIONS_USED; do
  vnet=cg-vnet-$region; prefix=10.$n; n=$((n + 10))
  if exists az network vnet show -g "$RG" -n "$vnet"; then ok "VNet $vnet ya existe"
  else
    log "Creando VNet $vnet"
    az network vnet create -g "$RG" -n "$vnet" -l "$region" --address-prefixes "$prefix.0.0/16" \
      --subnet-name default --subnet-prefixes "$prefix.1.0/24" --tags "${TAGS[@]}" -o none
  fi
done

# --- 3. IPs públicas estáticas (se crean antes de las VMs para usarlas en los NSG) --
public_ip() { az network public-ip show -g "$RG" -n "$1-ip" --query ipAddress -o tsv; }
for vm in "${VMS[@]}"; do
  name=$(field "$vm" 0); region=$(field "$vm" 1)
  if exists az network public-ip show -g "$RG" -n "$name-ip"; then
    same_region Microsoft.Network/publicIPAddresses "$name-ip" "$region"; ok "IP pública $name-ip ya existe"
  else
    log "Creando IP pública estática $name-ip"
    az network public-ip create -g "$RG" -n "$name-ip" -l "$region" --sku Standard \
      --allocation-method Static --tags "${TAGS[@]}" -o none
  fi
done
TOOLS_PUBLIC=$(public_ip cg-tools)
TOOLS_IP="$TOOLS_PUBLIC/32"
ENV_IPS="$(public_ip cg-dev)/32 $(public_ip cg-stage)/32 $(public_ip cg-prod)/32"

# --- 4. NSG por VM con reglas de mínimo privilegio -----------------------------------
rule() {  # rule NSG NOMBRE PRIORIDAD PUERTO ORIGEN...
  local nsg=$1 name=$2 prio=$3 port=$4; shift 4
  local args=(-g "$RG" --nsg-name "$nsg" -n "$name" --priority "$prio" --direction Inbound --access Allow
              --protocol Tcp --destination-port-ranges "$port" --source-address-prefixes "$@" -o none)
  if exists az network nsg rule show -g "$RG" --nsg-name "$nsg" -n "$name"; then
    az network nsg rule update "${args[@]}"   # converge si cambió una IP de origen
    ok "Regla $nsg/$name al día"
  else
    log "Regla $nsg/$name: $port <- $*"; az network nsg rule create "${args[@]}"
  fi
}
for vm in "${VMS[@]}"; do
  name=$(field "$vm" 0); region=$(field "$vm" 1); nsg=$name-nsg
  if exists az network nsg show -g "$RG" -n "$nsg"; then
    same_region Microsoft.Network/networkSecurityGroups "$nsg" "$region"; ok "NSG $nsg ya existe"
  else log "Creando NSG $nsg"; az network nsg create -g "$RG" -n "$nsg" -l "$region" --tags "${TAGS[@]}" -o none; fi
  if [[ $name == cg-tools ]]; then
    # shellcheck disable=SC2086
    {
      rule "$nsg" ssh-admin     100 22   "$ADMIN_CIDR"
      rule "$nsg" jenkins       110 8080 Internet
      rule "$nsg" sonar         120 9000 "$ADMIN_CIDR"
      rule "$nsg" nexus-ui      125 8081 "$ADMIN_CIDR"
      # El registro se usa por IP pública (dos regiones): también Jenkins, que
      # está en esta misma VM, entra por su IP pública (hairpin).
      rule "$nsg" registry      130 8082 $ENV_IPS "$TOOLS_IP" "$ADMIN_CIDR"
    }
  else
    rule "$nsg" ssh-jenkins-admin 100 22 "$TOOLS_IP" "$ADMIN_CIDR"
    rule "$nsg" web               110 80 Internet
  fi
done

# --- 5. VMs --------------------------------------------------------------------------
create_vm() {  # create_vm NOMBRE REGIÓN TAMAÑO DISCO -> 0 creada, 2 sin capacidad, 1 otro error
  local err; err=$(mktemp)
  if az vm create -g "$RG" -n "$1" -l "$2" --size "$3" --image "$IMAGE" \
       --admin-username "$ADMIN_USER" --ssh-key-values "$SSH_PUBKEY" \
       --vnet-name "cg-vnet-$2" --subnet default --nsg "$1-nsg" \
       --public-ip-address "$1-ip" --os-disk-size-gb "$4" --storage-sku StandardSSD_LRS \
       --tags "${TAGS[@]}" role="${1#cg-}" -o none --only-show-errors 2>"$err"; then
    rm -f "$err"; return 0
  fi
  if grep -q "SkuNotAvailable" "$err"; then rm -f "$err"; return 2; fi
  grep -E "Message:|ERROR" "$err" | head -5 >&2; rm -f "$err"; return 1
}

for vm in "${VMS[@]}"; do
  name=$(field "$vm" 0); region=$(field "$vm" 1); sizes=$(field "$vm" 2); disk=$(field "$vm" 3)
  if exists az vm show -g "$RG" -n "$name"; then
    ok "VM $name ya existe ($(az vm show -g "$RG" -n "$name" --query hardwareProfile.vmSize -o tsv))"; continue
  fi
  created=""
  IFS='|' read -r -a candidates <<<"$sizes"
  for size in "${candidates[@]}"; do
    log "Creando VM $name ($size, $region, disco ${disk} GB)"
    rc=0; create_vm "$name" "$region" "$size" "$disk" || rc=$?
    if ((rc == 0)); then created=$size; break; fi
    ((rc == 2)) || die "No se pudo crear $name con $size (error distinto a capacidad)"
    warn "$size sin capacidad en $region; probando el siguiente tamaño"
  done
  [[ -n $created ]] || die "Ningún tamaño disponible para $name en $region: $sizes"
  ok "VM $name creada con $created"
done

ok "Infraestructura lista"
status
cat <<EOF

Siguiente paso:
  tools:  ssh $ADMIN_USER@$TOOLS_PUBLIC   y   sudo REGISTRY_HOST=$TOOLS_PUBLIC ./infra/bootstrap/bootstrap-tools.sh
  envs:   sudo REGISTRY=$TOOLS_PUBLIC:8082 NEXUS_PULL_PASSWORD=... ./infra/bootstrap/bootstrap-env.sh <dev|stage|prod>
Apagar sin borrar (deja de cobrar cómputo):  az vm deallocate --ids \$(az vm list -g $RG --query "[].id" -o tsv)
EOF
