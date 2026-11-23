# Manual de operaciones de CircleGuard (borrador)

> **Estado: borrador (CG-109).** Todos los comandos salen de [README.md](../README.md),
> [deploy/README.md](../deploy/README.md), [jenkins/README.md](../jenkins/README.md) y
> los scripts de [`infra/`](../infra), más los README de
> [`infra/terraform`](../infra/terraform/README.md),
> [`deploy/helm/circleguard`](../deploy/helm/circleguard/README.md) y
> [`observability`](../observability/README.md), que ya están en `main`.
> El pipeline de `main` ya despliega a Kubernetes con Helm (`cgDeploy` con
> `CG_DEPLOY_TARGET=helm`, el valor por defecto) y conserva Compose como respaldo.
> Lo que aún no tiene evidencia en el repo (p. ej. `docs/change-management/rollback.md`,
> CG-104) queda marcado como **pendiente: se completa cuando exista en main**.
> No se inventó ningún comando.

## Contenido

1. [Levantar todo desde cero](#1-levantar-todo-desde-cero)
2. [Desplegar](#2-desplegar)
3. [Aprobar producción](#3-aprobar-producción)
4. [Hacer rollback](#4-hacer-rollback)
5. [Ver los dashboards](#5-ver-los-dashboards)
6. [Qué hacer ante cada alerta](#6-qué-hacer-ante-cada-alerta)
7. [Destruir la infraestructura](#7-destruir-la-infraestructura)

## Requisitos previos

| Herramienta | Para qué |
|---|---|
| Azure CLI (`az`) con sesión iniciada (`az login`) | crear la infraestructura |
| Llave SSH (`~/.ssh/id_ed25519.pub`) | administrar las VMs |
| Bash, `git`, `curl`, `jq` | scripts de `infra/` |

Fuente: [README.md §2](../README.md).

## 1. Levantar todo desde cero

### 1.1 Parte 1 (vigente en `main`): VMs con Docker Compose

Desde la raíz del repositorio, dos comandos idempotentes (fuente: [README.md §3](../README.md)):

```bash
./infra/azure/provision.sh       # VMs, red, IPs estáticas y NSG en Azure (~10 min)
./infra/azure/bootstrap-all.sh   # configura los 4 servidores (~15 min la primera vez)
```

- Un solo ambiente: `./infra/azure/bootstrap-all.sh dev prod` (acepta ambientes como argumento).
- Ver estado e IPs: `./infra/azure/provision.sh --status`.
- Variables opcionales de `provision.sh`: `ADMIN_CIDR`, `SSH_PUBKEY`, `RG`.
- Resultado esperado: Jenkins, SonarQube y Nexus en cg-tools, y dev/stage/prod
  preparados en `/opt/circleguard/<env>` (detalle en [deploy/README.md](../deploy/README.md)).
- Los secretos se generan en cada servidor (`/opt/circleguard/<env>/.env` y
  `/opt/cg-tools/.env`, ambos `chmod 600`). Nunca se sobrescribe un `.env` existente.

### 1.2 Configurar Jenkins (una sola vez)

Pasos completos en [jenkins/README.md](../jenkins/README.md):

1. Credenciales: `github-pat`, `nexus-docker`, `sonar-token`, `deploy-ssh`, `discord-webhook`.
2. Variables globales: `CG_REGISTRY`, `CG_DEV_HOST`, `CG_STAGE_HOST`, `CG_PROD_HOST` (y opcionales).
3. Shared Library `circleguard-lib` (Library Path `jenkins/`, rama `main`).
4. Servidor SonarQube `sonar` y su webhook hacia Jenkins.
5. Job multibranch `circleguard` y job `circleguard-rollback` (Script Path `jenkins/Jenkinsfile.rollback`).
6. Usuarios `luis`, `simon` y `dario` (aprobadores de producción).

Jenkins local de prueba (fuente: jenkins/README.md §1):

```bash
cd jenkins/local
docker compose up -d --build
docker compose exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

### 1.3 Parte 2: Terraform → clúster k3s → Jenkins → primer despliegue

Fuente: [infra/terraform/README.md, «Reconstruir todo desde cero»](../infra/terraform/README.md).
Requiere `az login` y los permisos del [§Requisitos](#requisitos-previos). Los pasos 1 a 6
son los del README, en orden:

```bash
./infra/terraform/bootstrap/state.sh                                          # 1. estado remoto (una vez)
cp infra/terraform/envs/shared/terraform.tfvars{.example,}                    # 2. editar: tu IP y correo
terraform -chdir=infra/terraform/envs/shared init && terraform -chdir=infra/terraform/envs/shared apply
for e in dev stage prod; do terraform -chdir=infra/terraform/envs/$e init && terraform -chdir=infra/terraform/envs/$e apply; done
./infra/azure/bootstrap-all.sh tools                                          # 3. Jenkins, Sonar y Nexus
KUBECONFIG=~/.kube/circleguard.yaml ./infra/k8s/tls/install.sh                # 4. cert-manager
KUBECONFIG=~/.kube/circleguard.yaml ./infra/k8s/jenkins-access.sh             # 5. kubeconfig de Jenkins
for e in dev stage prod; do ./deploy/helm/create-secrets.sh $e; done          # 6. Secrets desde Key Vault
```

- `envs/shared` crea red, NSG, el nodo k3s (`cg-k3s`), la VM `cg-tools`, alertas y
  presupuesto; `envs/{dev,stage,prod}` crean Key Vault y respaldos.
- Los comandos de Kubernetes usan `KUBECONFIG=~/.kube/circleguard.yaml`. Ese archivo
  (kubeconfig de administrador) se baja por SSH desde el nodo k3s; ver «Kubeconfig de
  administrador» en [infra/terraform/README.md](../infra/terraform/README.md). La API (6443)
  solo responde a las IP del NSG. Nunca va al repositorio ni se comparte.
- Los Secrets: ver [docs/infra/secretos.md](infra/secretos.md).
- Backend del estado y su estado: `./infra/terraform/bootstrap/state.sh --status`.
- Después del paso 3, configurar Jenkins con §1.2 y agregar la credencial
  `kubeconfig-circleguard` (Secret file) con el archivo del paso 5
  (fuente: comentario de `infra/k8s/jenkins-access.sh`).
- Credenciales de Terraform en Jenkins: `azure-sp`, `azure-tenant-id`, `azure-subscription-id`
  (fuente: infra/terraform/README.md).
- Primer despliegue de la aplicación en `circleguard-dev`: ver [§2.3](#23-en-kubernetes).
- Observabilidad: ver [§5.2](#52-observabilidad-parte-2).
- Costo aproximado de lo desplegado: ~USD 10–11 por día (infra/terraform/README.md).
  Detalle en [docs/infra/costos.md](infra/costos.md).

## 2. Desplegar

### 2.1 Por el pipeline (vigente)

Fuente: [README.md §5–6](../README.md) y [jenkins/README.md](../jenkins/README.md).

1. Los PR y ramas distintas de `main` solo corren CI (compilar, pruebas, Sonar, integración).
2. El build de `main` se lanza **a mano**: Jenkins → `circleguard` → `main` → *Build Now*
   (Nexus no permite volver a publicar una versión, `ALLOW_ONCE`).
3. Solo `feat`, `fix`, `perf`, `refactor`, `build` o `!` generan versión. Con solo
   `docs`/`test`/`ci`/`chore` no se publica ni se despliega.
4. Con versión nueva (fuente: [`Jenkinsfile`](../Jenkinsfile)): versión → publica las
   imágenes como `vX.Y.Z-SNAPSHOT.<build>` (con Trivy) → deploy dev → E2E → deploy stage →
   **aprobación** → promover la SNAPSHOT a release → tag y release notes → deploy prod.
   Si se rechaza la aprobación no se crea ninguna release.
5. Los pasos de deploy usan `cgDeploy`: por defecto `helm upgrade --install --atomic --wait`
   en el namespace del ambiente (`circleguard-dev|stage|prod`), con el kubeconfig de la
   credencial `kubeconfig-circleguard`. Con `CG_DEPLOY_TARGET=compose` vuelve a SSH + Compose
   (fuente: [jenkins/README.md](../jenkins/README.md), `jenkins/vars/cgDeploy.groovy`).

### 2.2 A mano (vigente)

Desde cg-tools, por SSH al servidor del ambiente (el SSH solo se permite desde cg-tools).
Fuente: [deploy/README.md, «Desplegar a mano»](../deploy/README.md):

```bash
cd /opt/circleguard/<ambiente>
export VERSION=v1.2.3
export REGISTRY=<ip-tools>:8082   # opcional: ya está en .env
docker compose pull --quiet
docker compose up -d --remove-orphans --wait --wait-timeout 300
echo "$VERSION" > .deployed-version
docker compose ps
```

Para operar sobre lo ya desplegado (`logs`, `ps`, `down` piden `VERSION`):

```bash
export VERSION=$(cat .deployed-version)
docker compose logs -f auth-service
```

Verificación de salud en local (fuente: deploy/README.md, «Probar en local»):
`curl -s http://localhost/healthz` y `curl -s http://localhost/svc/auth/actuator/health/readiness`.

### 2.3 En Kubernetes

**A mano** (fuente: [deploy/helm/circleguard/README.md](../deploy/helm/circleguard/README.md)).
Primero, una vez por ambiente, crear los Secrets; luego instalar o actualizar el chart:

```bash
export KUBECONFIG=~/.kube/circleguard.yaml
./deploy/helm/create-secrets.sh dev
helm upgrade --install circleguard deploy/helm/circleguard -n circleguard-dev \
  -f deploy/helm/circleguard/values-dev.yaml \
  --set global.imageTag=v1.0.2-SNAPSHOT.0 --set global.registry=<registro-nexus>:8082 \
  --atomic --wait --timeout 15m
```

- Cambiar `dev` por `stage` o `prod` (y `values-<env>.yaml`) según el ambiente. Los valores de
  `imageTag` y `registry` del ejemplo son los del README; usar la versión real publicada.
- El chart **falla** si falta el tag, si es `latest` o si no cumple `vX.Y.Z[-SNAPSHOT.N]`.
- `--atomic` revierte solo si el despliegue falla.
- Solo el cliente web queda público (Ingress con TLS); los backends son internos.

**Por el pipeline:** `cgDeploy` ejecuta el mismo `helm upgrade --install --atomic --wait`
(ver [§2.1](#21-por-el-pipeline-vigente)); si un pod no queda listo, Helm deshace el cambio y
el build queda en rojo.

## 3. Aprobar producción

Fuente: [README.md §6](../README.md).

1. Abrir el build de `main` en Jenkins.
2. En la etapa *Aprobación PROD*, pulsar **Desplegar**.
3. Solo pueden aprobar `luis`, `simon` o `dario` (variable `CG_PROD_APPROVERS`).
4. La aprobación espera 2 horas. Si se rechaza o vence, no llega nada a prod.
5. Antes de aprobar, revisar la política en
   [docs/change-management/politica-de-aprobacion.md](change-management/politica-de-aprobacion.md).

Cambios de la Parte 2 en esta etapa (convención SNAPSHOT → release, re-etiquetado de
la misma imagen): ver [versionado-y-releases.md](change-management/versionado-y-releases.md).
La promoción la hace `cgPromote` (copia con `crane` y compara digests) y luego `cgRelease`
(fuente: jenkins/README.md). Captura de una aprobación real: pendiente de evidencia.

## 4. Hacer rollback

Las imágenes son inmutables en Nexus: volver atrás es desplegar la versión anterior
(fuente: [deploy/README.md, «Rollback»](../deploy/README.md)).

### 4.1 Por Jenkins (recomendado)

Job `circleguard-rollback` → *Build with Parameters*:

| Parámetro | Ejemplo |
|---|---|
| `TARGET_ENV` | `prod` |
| `ROLLBACK_VERSION` | `v1.0.0` (debe existir en Nexus) |
| `REASON` | motivo; queda en el log y en Discord |

En prod pide aprobación. No reconstruye nada; tarda ~4 min.

### 4.2 A mano (emergencia)

```bash
cd /opt/circleguard/<ambiente>
export VERSION=<anterior> && docker compose up -d --wait
echo "$VERSION" > .deployed-version
```

**Limitación:** el esquema de base de datos no se revierte (Flyway solo avanza), por
eso las migraciones deben ser compatibles con la versión anterior (expand/contract).

### 4.3 En Kubernetes

Con `CG_DEPLOY_TARGET=helm` (el valor por defecto) el mismo job `circleguard-rollback`
(§4.1) ejecuta un `helm upgrade` con la versión anterior en el namespace del ambiente, con
`--atomic --wait`: si esa versión no arranca, Helm deja el ambiente como estaba
(fuente: comentario de `jenkins/Jenkinsfile.rollback`). Se usa a propósito en vez de
`helm rollback N`, para que la versión pedida quede explícita.

- A mano: repetir el `helm upgrade --install` de [§2.3](#23-en-kubernetes) con `global.imageTag`
  de una versión ya publicada.
- El plan formal (quién decide y aprueba, verificación, evidencia y limitaciones) está en
  [`docs/change-management/rollback.md`](change-management/rollback.md) (CG-104).
- Los comandos `helm history` y `kubectl rollout history` no están en ningún README del
  repo: pendiente de evidencia.

## 5. Ver los dashboards

### 5.1 Vigente (Parte 1)

| Qué | Dónde |
|---|---|
| Pipeline y builds | Jenkins, http://<ip-tools>:8080 |
| Calidad y cobertura | SonarQube, http://<ip-tools>:9000 (solo IPs del equipo) |
| Imágenes publicadas | Nexus, http://<ip-tools>:8081 (solo IPs del equipo) |
| Notificaciones de builds | Discord, canal `#circleguard-ci` |
| Estado de contenedores | `docker compose ps` en el ambiente |

### 5.2 Observabilidad (Parte 2)

Fuente: [observability/README.md](../observability/README.md). Todo corre en el namespace
`observability`. Instalar o actualizar (idempotente; la URL de Discord nunca se escribe en el repo):

```bash
export KUBECONFIG=~/.kube/circleguard.yaml
DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/...' ./observability/install.sh
```

Acceso (los imprime `install.sh` al terminar):

```bash
kubectl -n observability port-forward svc/kube-prometheus-stack-grafana 3000:80
kubectl -n observability port-forward svc/kube-prometheus-stack-prometheus 9090
kubectl -n observability port-forward svc/kube-prometheus-stack-alertmanager 9093
kubectl -n observability port-forward svc/jaeger 16686
# contraseña de Grafana:
kubectl -n observability get secret kube-prometheus-stack-grafana -o jsonpath='{.data.admin-password}' | base64 -d
```

| Qué | Dónde |
|---|---|
| 8 dashboards técnicos (uno por servicio) y 1 de negocio | Grafana, carpeta [`observability/dashboards`](../observability/dashboards) |
| Logs | Grafana → *Explore* → Loki: `{namespace="circleguard-dev", app="promotion-service"}` |
| Logs por traza | `{namespace=~"circleguard-.*"} \|= "<traceId>"` |
| Trazas form → Kafka → promotion → notification | Jaeger |

## 6. Qué hacer ante cada alerta

Las 3 alertas críticas llegan a Discord vía Alertmanager (fuente:
[observability/README.md](../observability/README.md), reglas en `observability/k8s/alerts.yaml`).
Los pasos de diagnóstico usan solo comandos que aparecen en los README; las acciones de
corrección más allá de ellos son **pendiente de evidencia** (no hay runbook en el repo).

| Alerta | Condición | Diagnóstico | Acción |
|---|---|---|---|
| `CircleGuardServiceDown` | Un servicio no responde 1 min o se quedó sin pods | `kubectl -n circleguard-<env> get pods`; logs del servicio en Loki (§5.2) | Si fue un escalado a 0 intencional, volver a `--replicas=1` (`kubectl -n circleguard-dev scale deployment <svc>-service --replicas=1`). Otras causas: pendiente de evidencia |
| `CircleGuardHighErrorRate` | Más de 5 % de respuestas 5xx durante 5 min | Dashboard del servicio en Grafana; trazas en Jaeger; logs por `traceId` | Revisar dependencias (el README cita identity caído como causa de 500 en login). Corrección: pendiente de evidencia |
| `CircleGuardHighLatencyP95` | p95 de latencia HTTP > 1 s durante 5 min | Dashboard del servicio; carga con Locust | Escalar o revisar el HPA (gateway, form y promotion: 70 % de CPU). Detalle: pendiente de evidencia |

Si una alerta persiste tras un despliegue, considerar el rollback ([§4](#4-hacer-rollback)).

Mientras tanto, los fallos que sí tienen evidencia en la documentación:

| Síntoma | Causa documentada | Qué hacer |
|---|---|---|
| `docker compose` se detiene con un mensaje sobre una variable | falta un secreto obligatorio en `.env` (`${X:?...}`) | completar el `.env` del ambiente (ver tabla de variables en deploy/README.md) |
| `up --wait` expira | un contenedor no quedó `healthy` en 300 s | `docker compose ps` y `docker compose logs -f <servicio>` |
| El build de `main` espera sin terminar en Sonar | falta el webhook de SonarQube hacia Jenkins | configurarlo (jenkins/README.md §5) |
| Se pierden los archivos subidos en file-service | se guardan en `/tmp/uploads` y se pierden al recrear el contenedor | limitación conocida (deploy/README.md, «Limitaciones conocidas») |
| Login responde 500 (`Connection refused`) | `IdentityClient` usa `http://localhost:8083` fijo | limitación conocida |

## 7. Destruir la infraestructura

> **Solo después de la evaluación.** Destruir borra las VMs, los volúmenes y los datos.
> Confirmar con el equipo antes de ejecutar cualquiera de estos comandos.

### 7.1 Parte 1 (vigente)

Fuente: [README.md §9](../README.md):

```bash
./infra/azure/provision.sh --status                                             # estado e IPs
az vm deallocate --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)  # apagar (solo se paga disco e IP)
az vm start      --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)  # encender (las IPs no cambian)
az group delete -n circleguard-rg --yes                                         # destruir todo
```

Encendidas cuestan unos USD 0,46 por hora; apagadas, unos USD 0,5 por día.

### 7.2 Parte 2 (`terraform destroy`)

Fuente: «Destruir la infraestructura» en [infra/terraform/README.md](../infra/terraform/README.md).
Primero los ambientes y al final `shared`, en orden inverso a la creación:

```bash
for e in prod stage dev; do terraform -chdir=infra/terraform/envs/$e destroy; done   # Key Vault y respaldos
terraform -chdir=infra/terraform/envs/shared destroy                                 # clúster, tools, red y alertas
```

- **Antes:** exportar los dashboards de Grafana y los reportes de Jenkins; los discos de
  las VMs se borran con `shared`.
- Los Key Vault quedan en borrado suave. Para recrearlos con el mismo nombre:
  `az keyvault list-deleted` y `az keyvault purge -n <nombre>`.
- El estado (`cg-tfstate-rg`) no lo toca ningún `destroy`; se borra al final, si ya no
  se va a usar: `az group delete -n cg-tfstate-rg`.
- Verificar que no quede nada con costo: `az resource list --tag project=circleguard -o table`
  debe salir vacío.
- Apagar sin borrar: `az vm deallocate --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)`.
- `helm.sh/resource-policy: keep` protege el PVC `file-service-uploads` (README del chart).
