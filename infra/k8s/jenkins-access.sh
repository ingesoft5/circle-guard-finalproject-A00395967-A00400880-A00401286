#!/usr/bin/env bash
# Da acceso a Jenkins al clúster con mínimo privilegio y genera su kubeconfig (CG-85).
#
#   KUBECONFIG=~/.kube/circleguard.yaml ./infra/k8s/jenkins-access.sh [archivo-salida]
#
# Crea la ServiceAccount cicd/jenkins-deployer con un Role solo en
# circleguard-dev, -stage y -prod, y escribe un kubeconfig (chmod 600, por
# defecto ~/.kube/jenkins-circleguard.yaml) para la credencial
# "kubeconfig-circleguard" (Secret file) de Jenkins. NUNCA va al repo ni al chat.
# Idempotente.
set -Eeuo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
. "$HERE/../bootstrap/lib.sh"
OUT=${1:-$HOME/.kube/jenkins-circleguard.yaml}
NAMESPACES=(circleguard-dev circleguard-stage circleguard-prod)

kubectl apply -f "$HERE/jenkins-rbac.yaml" >/dev/null
for ns in "${NAMESPACES[@]}"; do kubectl apply -n "$ns" -f "$HERE/jenkins-role.yaml" >/dev/null; done
ok "ServiceAccount cicd/jenkins-deployer con Role en ${NAMESPACES[*]}"

for _ in {1..20}; do
  TOKEN=$(kubectl get secret -n cicd jenkins-deployer-token -o jsonpath='{.data.token}' 2>/dev/null | base64 -d) && [[ -n $TOKEN ]] && break
  sleep 1
done
[[ -n ${TOKEN:-} ]] || die "El token de jenkins-deployer no se generó"
SERVER=$(kubectl config view --minify -o jsonpath='{.clusters[0].cluster.server}')
CA=$(kubectl config view --minify --raw -o jsonpath='{.clusters[0].cluster.certificate-authority-data}')

umask 077
cat > "$OUT" <<KUBE
apiVersion: v1
kind: Config
clusters:
  - name: circleguard
    cluster:
      server: $SERVER
      certificate-authority-data: $CA
users:
  - name: jenkins-deployer
    user:
      token: $TOKEN
contexts:
  - name: jenkins@circleguard
    context:
      cluster: circleguard
      user: jenkins-deployer
      namespace: circleguard-dev
current-context: jenkins@circleguard
KUBE
ok "kubeconfig de Jenkins en $OUT (chmod 600). Súbelo como 'kubeconfig-circleguard' (Secret file)."

# Prueba de mínimo privilegio con el propio kubeconfig de Jenkins.
can() { KUBECONFIG=$OUT kubectl auth can-i "$@" 2>/dev/null || true; }
printf '  %-48s %s\n' "deployments en circleguard-prod" "$(can create deployments -n circleguard-prod)" \
  "secrets en kube-system" "$(can get secrets -n kube-system)" \
  "pods en observability" "$(can list pods -n observability)" \
  "rolebindings en circleguard-dev (escalar)" "$(can create rolebindings -n circleguard-dev)" \
  "pods/exec en circleguard-prod" "$(can create pods/exec -n circleguard-prod)" \
  "namespaces (crear)" "$(can create namespaces)"
