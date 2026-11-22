# Módulo `cluster`

Una VM Ubuntu 22.04 que, con cloud-init, instala **k3s** (versión fija) como
server y agent a la vez. k3s ya trae Traefik (Ingress), metrics-server (HPA),
CoreDNS y local-path (PVC).

Al arrancar deja listos:

- `tls-san` con la IP pública, para que Jenkins use la API (`:6443`).
- `registries.yaml` para bajar imágenes de Nexus por HTTP. Las credenciales
  van en un `imagePullSecret` por namespace, no en el nodo.
- Los namespaces de `var.namespaces`. Los de la app llevan Pod Security
  `baseline` (enforce) y `restricted` (warn).
- Secrets cifrados en reposo (`secrets-encryption`).

**Por qué un solo nodo:** la cuenta gratuita de Azure permite 4 vCPU por región.
Una E4as_v5 (4 vCPU, 32 GB) da toda la memoria en un nodo. Los tres ambientes
y la observabilidad necesitan unos 24,5 GB con los límites del compose. Para
más nodos, se instancia el módulo otra vez como agente en otra región o con
más cuota.

`custom_data` se ignora después de crear la VM: cambiar cloud-init no debe
recrear el nodo ni borrar los PVC.

Salidas: `api_server`, `private_ip`, `kubeconfig_command`, `vm_id`.
