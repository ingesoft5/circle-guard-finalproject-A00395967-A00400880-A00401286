#!/usr/bin/env bash
# Crea, UNA sola vez, donde vive el estado remoto de Terraform (CG-79).
#
#   ./infra/terraform/bootstrap/state.sh            # crea o converge (idempotente)
#   ./infra/terraform/bootstrap/state.sh --status   # solo muestra lo que existe
#
# Por qué un script y no Terraform: el backend tiene que existir ANTES de
# `terraform init` (problema del huevo y la gallina). Son 3 recursos que no
# cambian, así que se crean con az CLI y se documentan aquí.
#
# Qué crea (grupo propio, separado de los ambientes, para que un
# `terraform destroy` de dev/stage/prod nunca borre el estado):
#
#   cg-tfstate-rg              grupo de recursos
#   cgtfstate<8 hex>           Storage Account (Standard_LRS, TLS 1.2, solo HTTPS,
#                              sin acceso público a blobs, sin llaves compartidas)
#   tfstate                    contenedor privado; un blob por ambiente
#                              (circleguard/<env>.tfstate)
#
# Protección del estado: versionado de blobs y borrado suave (14 días), para
# poder recuperar un estado dañado o borrado por error.
#
# Bloqueo: el backend azurerm toma un *lease* sobre el blob durante plan/apply;
# no hace falta una tabla aparte (el equivalente a DynamoDB en S3).
#
# Acceso: solo con Azure AD (use_azuread_auth en backend.tf). Este script le da
# "Storage Blob Data Contributor" sobre la cuenta al usuario que lo ejecuta y,
# si se define EXTRA_PRINCIPAL_ID, a otro principal (p. ej. el service
# principal de Jenkins, CG-89).
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../../bootstrap/lib.sh"
trap 'die "Falló en la línea $LINENO: $BASH_COMMAND"' ERR

LOCATION=${LOCATION:-westus2}
RG=${STATE_RG:-cg-tfstate-rg}
CONTAINER=tfstate
TAGS=(project=circleguard env=shared purpose=tfstate)

command -v az >/dev/null || die "Falta az CLI"
az account show -o none 2>/dev/null || die "Inicia sesión con: az login"
SUB_ID=$(az account show --query id -o tsv)
# Nombre global único y estable por suscripción: siempre el mismo al repetir.
SA=${STATE_SA:-cgtfstate${SUB_ID:0:8}}

exists() { "$@" --only-show-errors -o none &>/dev/null; }

if [[ ${1:-} == --status ]]; then
  az storage account show -g "$RG" -n "$SA" \
    --query "{cuenta:name, region:location, tls:minimumTlsVersion, llaves:allowSharedKeyAccess, publico:allowBlobPublicAccess}" -o table
  az storage blob list --account-name "$SA" -c "$CONTAINER" --auth-mode login \
    --query "[].{blob:name, bytes:properties.contentLength, lease:properties.lease.state}" -o table
  exit 0
fi

log "Suscripción: $(az account show --query name -o tsv) · cuenta de estado: $SA ($LOCATION)"

# --- 1. Grupo de recursos ------------------------------------------------------
if exists az group show -n "$RG"; then ok "Grupo $RG ya existe"
else log "Creando grupo $RG"; az group create -n "$RG" -l "$LOCATION" --tags "${TAGS[@]}" -o none; fi

# --- 2. Storage Account endurecida ---------------------------------------------
sa_args=(-g "$RG" -n "$SA" --min-tls-version TLS1_2 --https-only true
         --allow-blob-public-access false --allow-shared-key-access false -o none)
if exists az storage account show -g "$RG" -n "$SA"; then
  az storage account update "${sa_args[@]}"   # converge si alguien cambió algo a mano
  ok "Storage Account $SA ya existe (configuración al día)"
else
  log "Creando Storage Account $SA"
  az storage account create "${sa_args[@]}" -l "$LOCATION" --sku Standard_LRS --kind StorageV2 \
    --tags "${TAGS[@]}"
fi

# Versionado y borrado suave: repetirlo no cambia nada.
az storage account blob-service-properties update -g "$RG" --account-name "$SA" \
  --enable-versioning true --enable-delete-retention true --delete-retention-days 14 \
  --enable-container-delete-retention true --container-delete-retention-days 14 -o none
ok "Versionado y borrado suave (14 días) activos"

# --- 3. Permiso de datos (Azure AD) --------------------------------------------
SA_ID=$(az storage account show -g "$RG" -n "$SA" --query id -o tsv)
grant() {  # grant OBJECT_ID TIPO
  if [[ -n $(az role assignment list --assignee "$1" --scope "$SA_ID" \
        --role "Storage Blob Data Contributor" --query "[0].id" -o tsv) ]]; then
    ok "Permiso de datos ya asignado a $1"
  else
    log "Asignando Storage Blob Data Contributor a $1"
    az role assignment create --assignee-object-id "$1" --assignee-principal-type "$2" \
      --role "Storage Blob Data Contributor" --scope "$SA_ID" -o none
  fi
}
grant "$(az ad signed-in-user show --query id -o tsv)" User
[[ -n ${EXTRA_PRINCIPAL_ID:-} ]] && grant "$EXTRA_PRINCIPAL_ID" ServicePrincipal

# --- 4. Contenedor privado -----------------------------------------------------
# El permiso recién asignado tarda hasta unos minutos en propagarse.
for i in {1..12}; do
  if az storage container show -n "$CONTAINER" --account-name "$SA" --auth-mode login -o none 2>/dev/null; then
    ok "Contenedor $CONTAINER ya existe"; break
  elif az storage container create -n "$CONTAINER" --account-name "$SA" --auth-mode login \
         --public-access off -o none 2>/dev/null; then
    ok "Contenedor $CONTAINER creado"; break
  fi
  ((i == 12)) && die "No se pudo crear el contenedor (¿el permiso aún no se propaga?). Vuelve a ejecutar."
  warn "Esperando a que el permiso se propague ($i/12)…"; sleep 15
done

cat <<EOF

Backend listo. Cada ambiente lo usa desde infra/terraform/envs/<env>/backend.tf:
  resource_group_name  = "$RG"
  storage_account_name = "$SA"
  container_name       = "$CONTAINER"
EOF
