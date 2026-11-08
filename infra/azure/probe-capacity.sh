#!/usr/bin/env bash
# Sondea qué tamaños de VM tienen capacidad en cada región permitida, SIN crear
# nada: valida una plantilla ARM (az deployment group validate), que corre el
# mismo chequeo previo ("preflight") que az vm create.
#
#   ./infra/azure/probe-capacity.sh                          # tamaños y regiones por defecto
#   REGIONS="westus2 chilecentral" SIZES="Standard_D2s_v3" ./infra/azure/probe-capacity.sh
#
# Sirve para elegir las listas de tamaños de provision.sh cuando Azure responde
# SkuNotAvailable ("Capacity Restrictions") aunque haya cuota.
set -Euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
RG=${RG:-circleguard-rg}
REGIONS=${REGIONS:-"westus2 northcentralus chilecentral brazilsouth spaincentral"}
SIZES=${SIZES:-"Standard_B2s_v2 Standard_B2ms Standard_D2as_v4 Standard_D2s_v3 Standard_D4s_v3 Standard_D4as_v4 Standard_E2s_v3 Standard_E2as_v4 Standard_B4ms Standard_B4s_v2"}

printf '%-15s %-18s %s\n' REGIÓN TAMAÑO RESULTADO
for region in $REGIONS; do
  for size in $SIZES; do
    out=$(az deployment group validate -g "$RG" --template-file "$HERE/probe-vm.json" \
            --parameters location="$region" vmSize="$size" --only-show-errors -o none 2>&1)
    if [[ -z $out ]]; then result=OK
    elif grep -q SkuNotAvailable <<<"$out"; then result="sin capacidad"
    else result="error: $(grep -oE '\(([A-Za-z]+)\)' <<<"$out" | sort -u | tr '\n' ' ')"
    fi
    printf '%-15s %-18s %s\n' "$region" "$size" "$result"
  done
done
