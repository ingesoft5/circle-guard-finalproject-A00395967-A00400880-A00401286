# Arquitectura en Kubernetes (Parte 2)

Diagrama de lo que corre dentro del clúster k3s `cg-k3s`. Fuentes: el chart
[`deploy/helm/circleguard`](../../deploy/helm/circleguard) (`values.yaml`,
`templates/`), [`observability/`](../../observability) e
[`infra/k8s/`](../../infra/k8s). La infraestructura de Azure que lo sostiene está en
[`terraform.md`](terraform.md).

```mermaid
flowchart TB
  U(("Usuarios")) -- "HTTPS 443<br/>&lt;env&gt;.40-64-120-3.sslip.io" --> TR

  subgraph KS["ns kube-system"]
    TR["Traefik (Ingress de k3s)<br/>middleware redirect-https"]
  end
  CM["cert-manager<br/>ClusterIssuer letsencrypt-prod"] -. "certificado TLS" .-> TR

  subgraph ENV["ns circleguard-dev / circleguard-stage / circleguard-prod<br/>(un release de Helm 'circleguard' por namespace)"]
    ING["Ingress web"]
    WEB["web (nginx :8080)<br/>proxy /svc/&lt;servicio&gt;/*"]
    subgraph JAVA["Servicios Java (Deployment + Service, usuario 10001)"]
      AUTH["auth :8180"]
      IDEN["identity :8083"]
      FORM["form :8086 · HPA"]
      PROMO["promotion :8088 · HPA"]
      NOTI["notification :8082"]
      GATE["gateway :8087 · HPA"]
      DASH["dashboard :8084"]
      FILE["file :8085"]
    end
    subgraph MW["Middleware (StatefulSet + PVC local-path)"]
      PG[("postgres :5432")]
      N4J[("neo4j :7687")]
      KAFKA[("kafka :29092")]
      ZK["zookeeper :2181"]
      REDIS[("redis :6379")]
      LDAP["openldap :389"]
    end
    PVC[("PVC uploads 2Gi<br/>/app/uploads")]
    CFG["ConfigMap circleguard-common<br/>Secret circleguard-secrets<br/>Secret nexus-pull"]
  end

  subgraph OBS["ns observability (observability/install.sh)"]
    PROM["Prometheus + Alertmanager<br/>(kube-prometheus-stack)"]
    GRAF["Grafana<br/>8 dashboards técnicos + 1 de negocio"]
    JAE["Jaeger"]
    LOKI["Loki"]
    ALLOY["Alloy"]
  end

  subgraph CICD["ns cicd"]
    RBAC["RBAC de Jenkins<br/>(infra/k8s/jenkins-rbac.yaml)"]
  end

  TR --> ING --> WEB
  WEB --> AUTH & IDEN & FORM & PROMO & NOTI & GATE & DASH & FILE
  AUTH --> PG & LDAP
  AUTH --> IDEN
  IDEN --> PG
  IDEN --> KAFKA
  FORM --> PG
  FORM --> KAFKA
  KAFKA --> PROMO
  PROMO --> PG & N4J & REDIS
  PROMO --> KAFKA
  NOTI --> KAFKA
  NOTI --> AUTH
  NOTI --> IDEN
  GATE --> REDIS
  DASH --> PG
  DASH --> PROMO
  KAFKA --- ZK
  FILE --- PVC
  CFG -. "env y secretos" .-> JAVA

  PROM -- "PodMonitor: /actuator/prometheus" --> JAVA
  PROM --> GRAF
  FORM & PROMO & NOTI -- "trazas OTLP :4318" --> JAE
  ALLOY -- "logs de los pods" --> LOKI
  LOKI --> GRAF
  PROM -- "3 alertas críticas" --> DISC(("Discord"))

  NEXUS(("Registro Nexus<br/>VM cg-tools :8082")) -. "pull de imágenes" .-> ENV
  JK(("Jenkins<br/>VM cg-tools")) -- "helm upgrade --install<br/>API 6443" --> RBAC
```

## Cómo leerlo (con evidencia)

| Elemento | Evidencia en el repo |
|---|---|
| Un namespace por ambiente y `observability`, `cicd` | `namespaces` en `infra/terraform/envs/shared/main.tf`; README de Terraform |
| Solo el web se publica por Ingress (Traefik); los backends son internos | comentario en `values.yaml` (`ingress`), `templates/web.yaml` |
| Nginx enruta `/svc/<servicio>/` a cada backend y bloquea `actuator` | `templates/web.yaml` (ConfigMap `web-nginx`) |
| 8 servicios Java con puertos, dependencias (`waitFor`) y secretos | `services:` en `values.yaml` |
| HPA en gateway, form y promotion | `hpa: true` en `values.yaml` |
| Middleware con PVC | `middleware:` en `values.yaml` |
| `file` monta un PVC en `/app/uploads` | `services.file` en `values.yaml` |
| Trazas desde form, promotion y notification | `tracing: true` y `tracing.endpoint` en `values.yaml` |
| Prometheus, Grafana, Alertmanager, Jaeger, Loki y Alloy | `observability/README.md` |
| Imágenes desde Nexus con `nexus-pull` | `global` en `values.yaml` |

## Notas

- Las flechas entre servicios salen de las URLs y de `waitFor` de `values.yaml`.
  Las de `form → kafka → promotion` corresponden al flujo por eventos descrito en
  [`arquitectura.md`](arquitectura.md); la dirección del consumo no se verificó de
  nuevo en el código para este diagrama (**pendiente de evidencia**).
- Cómo se abre Grafana desde fuera: el README de observabilidad indica
  `port-forward`; no hay un Ingress de Grafana en el chart (**pendiente de evidencia**
  si el equipo lo publicó aparte).
- Imagen PNG exportada: **pendiente** (no hay `mmdc` en este entorno). GitHub
  renderiza el Mermaid directamente.
