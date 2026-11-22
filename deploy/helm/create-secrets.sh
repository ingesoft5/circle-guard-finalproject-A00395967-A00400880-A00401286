#!/usr/bin/env bash
# Crea (o actualiza) los Secrets de un ambiente en Kubernetes (CG-82, CG-87).
#
#   ./deploy/helm/create-secrets.sh <dev|stage|prod>
#
# Flujo: Terraform genera los secretos al azar en el Key Vault del ambiente
# (infra/terraform/modules/secrets). Este script los copia al Secret
# "circleguard-secrets" del namespace circleguard-<env>, y crea "nexus-pull"
# (docker-registry) con el usuario de solo lectura de Nexus.
#
# Ningún valor pasa por el repo, por la salida ni por argumentos de comandos
# (se ven en `ps`): van a archivos temporales chmod 600 que se borran al salir.
#
# Requisitos: az (sesión con Key Vault Secrets User/Officer sobre el vault, desde
# una IP permitida en su firewall), kubectl con KUBECONFIG del clúster, y la
# contraseña de deploy-pull en NEXUS_PULL_PASSWORD o acceso SSH a cg-tools.
# Idempotente: `kubectl apply` deja el Secret igual si no cambió.
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../../infra/bootstrap/lib.sh"

ENV=${1:-}; [[ $ENV =~ ^(dev|stage|prod)$ ]] || die "Uso: $0 <dev|stage|prod>"
NS=circleguard-$ENV
SECRET=circleguard-secrets
KEYS=(postgres-password neo4j-password ldap-admin-password jwt-secret qr-secret vault-secret vault-salt)
TOOLS_RG=${TOOLS_RG:-circleguard-rg}
NEXUS_PULL_USER=${NEXUS_PULL_USER:-deploy-pull}

kubectl get ns "$NS" -o name >/dev/null || die "No existe el namespace $NS (¿KUBECONFIG?)"
VAULT=$(az keyvault list --query "[?tags.project=='circleguard' && tags.env=='$ENV'].name | [0]" -o tsv)
[[ -n $VAULT ]] || die "No encontré el Key Vault de $ENV (terraform apply en infra/terraform/envs/$ENV)"
TOOLS_IP=$(az network public-ip show -g "$TOOLS_RG" -n cg-tools-ip --query ipAddress -o tsv)
REGISTRY=${REGISTRY:-$TOOLS_IP:8082}

TMP=$(mktemp -d); chmod 700 "$TMP"; trap 'rm -rf "$TMP"' EXIT

log "Leyendo ${#KEYS[@]} secretos de $VAULT"
for k in "${KEYS[@]}"; do
  az keyvault secret show --vault-name "$VAULT" -n "$k" --query value -o tsv | tr -d '\n' > "$TMP/$k"
  [[ -s $TMP/$k ]] || die "El secreto $k está vacío en $VAULT"
done
from_files=(); for k in "${KEYS[@]}"; do from_files+=(--from-file="$k=$TMP/$k"); done
kubectl create secret generic "$SECRET" -n "$NS" "${from_files[@]}" --dry-run=client -o yaml |
  kubectl label --local -f - app.kubernetes.io/part-of=circleguard circleguard.io/source=key-vault -o yaml |
  kubectl apply -f - >/dev/null
ok "Secret $NS/$SECRET al día (${#KEYS[@]} claves desde $VAULT)"

# Contraseña de solo lectura de Nexus: la genera bootstrap-tools.sh en cg-tools.
if [[ -z ${NEXUS_PULL_PASSWORD:-} ]]; then
  ssh -o StrictHostKeyChecking=accept-new "azureuser@$TOOLS_IP" \
    "sudo grep '^NEXUS_PULL_PASSWORD=' /opt/cg-tools/.env | cut -d= -f2-" | tr -d '\n' > "$TMP/pull"
else
  printf '%s' "$NEXUS_PULL_PASSWORD" > "$TMP/pull"
fi
[[ -s $TMP/pull ]] || die "No obtuve la contraseña de $NEXUS_PULL_USER"
auth=$(printf '%s:%s' "$NEXUS_PULL_USER" "$(cat "$TMP/pull")" | base64 | tr -d '\n')
printf '{"auths":{"%s":{"username":"%s","auth":"%s"}}}' "$REGISTRY" "$NEXUS_PULL_USER" "$auth" > "$TMP/dockerconfig"
kubectl create secret generic nexus-pull -n "$NS" --type=kubernetes.io/dockerconfigjson \
  --from-file=.dockerconfigjson="$TMP/dockerconfig" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
ok "Secret $NS/nexus-pull al día (registro $REGISTRY, usuario $NEXUS_PULL_USER)"
