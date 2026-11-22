# Chart Helm `circleguard` (CG-81 a CG-84)

Un solo chart despliega los 8 microservicios, el cliente web y la middleware
de un ambiente en su namespace (`circleguard-<env>`). Es la traducción de
[`deploy/docker-compose.yml`](../../docker-compose.yml): mismas variables,
dependencias y límites de memoria.

```bash
helm upgrade --install circleguard deploy/helm/circleguard -n circleguard-dev \
  -f deploy/helm/circleguard/values-dev.yaml \
  --set global.imageTag=v1.0.2-SNAPSHOT.0 --set global.registry=4.255.240.15:8082 \
  --atomic --wait --timeout 15m
```

Antes del primer despliegue de cada ambiente:
`./deploy/helm/create-secrets.sh <env>` (Secrets desde Key Vault, ver
[docs/infra/secretos.md](../../../docs/infra/secretos.md)).

## Qué crea

| Recurso | Detalle |
|---|---|
| 8 Deployments `<svc>-service` + Service | Un solo template (`java-services.yaml`) con valores por servicio. Puerto `http`, etiquetas `app.kubernetes.io/part-of: circleguard` y `name: <svc>-service` (PodMonitor y Loki) |
| ConfigMaps | `circleguard-common` (CG_ENV, usuario de BD, Feature Toggles) y `<svc>-service-config` (URLs y valores no secretos) |
| Secrets (los crea `create-secrets.sh`, no el chart) | `circleguard-secrets` (7 claves) y `nexus-pull` (`imagePullSecrets`) |
| Web | Deployment + Service + **Ingress** (Traefik, TLS con cert-manager). Único componente público; los backends quedan internos. `/svc/*/actuator` responde 404 |
| Middleware | StatefulSet + Service + PVC (local-path) para PostgreSQL (con `init-db.sql` en ConfigMap), Neo4j, Kafka, Zookeeper, Redis y OpenLDAP |
| HPA | gateway, form y promotion: 70 % de CPU, de 1 a 2 réplicas (dev y stage) o de 1 a 3 (prod) |
| PVC `file-service-uploads` | Archivos subidos, con `helm.sh/resource-policy: keep` |

## Decisiones

- **Imagen:** `<global.registry>/circleguard/<svc>-service:<global.imageTag>`. El
  chart **falla** si falta el tag, si es `latest` o si no cumple
  `vX.Y.Z[-SNAPSHOT.N]`.
- **Sondas:** `startupProbe` (hasta 5 min) en `/actuator/health/liveness`, y
  después liveness y readiness de Actuator. Reemplazan los 120 s de
  `start_period` del compose sin retrasar los reinicios posteriores.
- **Orden de arranque:** cada servicio tiene un initContainer que espera a sus
  dependencias (`waitFor`, como `depends_on` del compose).
- **Recursos:** memoria request = límite (como `mem_limit`). La request de CPU
  es el consumo en reposo, para que los 3 ambientes quepan en el nodo de 4
  vCPU, y el límite de CPU cubre el arranque de la JVM.
- **Seguridad:** los contenedores propios cumplen Pod Security `restricted`
  (no root, sin capacidades, seccomp, raíz de solo lectura con `/tmp` en
  emptyDir). La middleware usa imágenes oficiales que arrancan como root:
  cumple `baseline` (que el namespace exige) y suma seccomp.
- **`enableServiceLinks: false`:** sin esto, Kubernetes inyecta variables
  `KAFKA_PORT` y `NEO4J_*` que rompen esas imágenes.
- **nginx del web:** la imagen trae la configuración de Docker (DNS
  `127.0.0.11` y nombres cortos). El chart monta la versión para Kubernetes
  (CoreDNS y nombres completos). El Dockerfile no se toca.
- **file-service:** `workingDir: /tmp` y PVC en `/tmp/uploads` hasta que el
  Dockerfile cree `/app/uploads` (CG-102). Después: `workingDir: ""` y
  `uploadsPath: /app/uploads`.

## Riesgos aceptados (Trivy, nivel HIGH)

`KSV-0014` en la middleware: postgres, neo4j, kafka, zookeeper, redis y
openldap escriben en su sistema de archivos al arrancar, así que no pueden ir
con raíz de solo lectura sin reescribir sus imágenes. No hay hallazgos
CRITICAL, así que la compuerta del pipeline pasa:

```bash
trivy config --severity CRITICAL,HIGH --helm-values deploy/helm/circleguard/values-dev.yaml \
  --helm-set global.registry=r:8082,global.imageTag=v0.0.0 deploy/helm/circleguard
```

Sin `--helm-set`, Trivy no puede renderizar el chart (registry y tag son
obligatorios) y **no lo escanea**. El pipeline debe pasar esos valores.
