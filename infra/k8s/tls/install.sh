#!/usr/bin/env bash
# Instala cert-manager y los emisores de Let's Encrypt (CG-86). Idempotente.
#
#   KUBECONFIG=~/.kube/circleguard.yaml ./infra/k8s/tls/install.sh
#
# Después, cada ambiente activa TLS en el chart con ingress.tls.enabled=true
# (values-<env>.yaml): cert-manager emite el certificado de <env>.<ip>.sslip.io
# y lo renueva solo, 30 días antes de vencer.
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../../bootstrap/lib.sh"
VERSION=${CERT_MANAGER_VERSION:-v1.20.5}

helm repo add jetstack https://charts.jetstack.io --force-update >/dev/null
helm upgrade --install cert-manager jetstack/cert-manager --namespace cert-manager --create-namespace \
  --version "$VERSION" --set crds.enabled=true \
  --set resources.requests.cpu=10m --set resources.requests.memory=64Mi \
  --set webhook.resources.requests.cpu=10m --set webhook.resources.requests.memory=32Mi \
  --set cainjector.resources.requests.cpu=10m --set cainjector.resources.requests.memory=64Mi \
  --wait --timeout 5m >/dev/null
ok "cert-manager $VERSION instalado"
kubectl apply -f "$HERE/cluster-issuers.yaml" >/dev/null
kubectl wait --for=condition=Ready clusterissuer/letsencrypt-prod --timeout=120s >/dev/null
ok "ClusterIssuer letsencrypt-prod listo y middleware redirect-https creado"
