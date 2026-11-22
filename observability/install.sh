#!/usr/bin/env bash
# Instala o actualiza la observabilidad de CircleGuard en el clúster (CG-93 a CG-97).
# Idempotente: correrlo de nuevo solo aplica las diferencias.
#
#   DISCORD_WEBHOOK_URL=https://discord.com/api/webhooks/... ./observability/install.sh
#
# Requiere kubectl y helm apuntando al clúster (KUBECONFIG). La URL de Discord
# se pasa por variable de entorno y queda en un Secret; nunca se escribe en el repo.
# Opcional: KPS_EXTRA_VALUES=<archivo> agrega valores propios de un clúster
# (por ejemplo, k3s dentro de Docker no admite el montaje de / de node-exporter).
set -euo pipefail

cd "$(dirname "$0")"
NS=observability
KPS_VERSION=93.4.1   # prometheus-community/kube-prometheus-stack
LOKI_VERSION=7.5.0   # grafana/loki
ALLOY_VERSION=1.14.4 # grafana/alloy

log() { printf '\n==> %s\n' "$*"; }

log "Namespace $NS"
kubectl create namespace "$NS" --dry-run=client -o yaml | kubectl apply -f -

log "Secret con el webhook de Discord para Alertmanager"
if [[ -n "${DISCORD_WEBHOOK_URL:-}" ]]; then
  kubectl -n "$NS" create secret generic alertmanager-discord \
    --from-literal=url="$DISCORD_WEBHOOK_URL" --dry-run=client -o yaml | kubectl apply -f -
elif ! kubectl -n "$NS" get secret alertmanager-discord >/dev/null 2>&1; then
  echo "Falta DISCORD_WEBHOOK_URL y el Secret alertmanager-discord no existe." >&2
  exit 1
else
  echo "Se mantiene el Secret existente."
fi

log "Repositorios de Helm"
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
helm repo add grafana https://grafana.github.io/helm-charts >/dev/null 2>&1 || true
helm repo update >/dev/null

log "Prometheus + Alertmanager + Grafana"
helm upgrade --install kube-prometheus-stack prometheus-community/kube-prometheus-stack \
  --version "$KPS_VERSION" --namespace "$NS" \
  --values values/kube-prometheus-stack.yaml ${KPS_EXTRA_VALUES:+--values "$KPS_EXTRA_VALUES"} \
  --wait --timeout 15m

log "Loki"
helm upgrade --install loki grafana/loki \
  --version "$LOKI_VERSION" --namespace "$NS" \
  --values values/loki.yaml --wait --timeout 10m

log "Alloy (envía los logs de los pods a Loki)"
helm upgrade --install alloy grafana/alloy \
  --version "$ALLOY_VERSION" --namespace "$NS" \
  --values values/alloy.yaml --wait --timeout 5m

log "Jaeger, PodMonitor y alertas de CircleGuard"
kubectl apply -f k8s/

log "Dashboards de Grafana (un ConfigMap por archivo JSON)"
for f in dashboards/*.json; do
  name="dashboard-$(basename "$f" .json)"
  kubectl -n "$NS" create configmap "$name" --from-file="$(basename "$f")=$f" \
    --dry-run=client -o yaml \
    | kubectl label --local -f - grafana_dashboard=1 -o yaml \
    | kubectl annotate --local -f - grafana_folder=CircleGuard -o yaml \
    | kubectl apply -f -
done

log "Listo"
kubectl -n "$NS" get pods
cat <<'EOF'

Acceso (port-forward):
  Grafana:      kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80
  Prometheus:   kubectl -n observability port-forward svc/kube-prometheus-stack-prometheus 9090
  Alertmanager: kubectl -n observability port-forward svc/kube-prometheus-stack-alertmanager 9093
  Jaeger:       kubectl -n observability port-forward svc/jaeger 16686
Contraseña de Grafana (usuario admin):
  kubectl -n observability get secret kube-prometheus-stack-grafana -o jsonpath='{.data.admin-password}' | base64 -d
EOF
