# Observabilidad de CircleGuard (CG-93 a CG-97)

Todo corre en el clúster, en el namespace `observability`:

| Pieza | Para qué | Archivo |
|---|---|---|
| Prometheus (kube-prometheus-stack) | Lee `/actuator/prometheus` de los 8 servicios cada 15 s | [`values/kube-prometheus-stack.yaml`](values/kube-prometheus-stack.yaml), [`k8s/podmonitor.yaml`](k8s/podmonitor.yaml) |
| Grafana | 8 dashboards técnicos (uno por servicio) y 1 de negocio | [`dashboards/`](dashboards) |
| Alertmanager | 3 alertas críticas enviadas a Discord | [`k8s/alerts.yaml`](k8s/alerts.yaml) |
| Jaeger | Trazas de form → Kafka → promotion → notification | [`k8s/jaeger.yaml`](k8s/jaeger.yaml) |
| Loki + Alloy | Logs de todos los pods, buscables por servicio y por `traceId` | [`values/loki.yaml`](values/loki.yaml), [`values/alloy.yaml`](values/alloy.yaml) |

## Instalar o actualizar

```bash
export KUBECONFIG=~/.kube/circleguard.yaml
DISCORD_WEBHOOK_URL='https://discord.com/api/webhooks/...' ./observability/install.sh
```

El script es idempotente: volver a correrlo solo aplica diferencias. La URL de
Discord queda en el Secret `alertmanager-discord`; nunca se escribe en el repo.
Al final imprime los `port-forward` para abrir Grafana, Prometheus,
Alertmanager y Jaeger, y el comando para leer la contraseña de Grafana.

## Lo que necesita el chart de la aplicación

Para que todo se conecte sin configuración extra, cada pod de servicio debe:

| Requisito | Por qué |
|---|---|
| Etiqueta `app.kubernetes.io/part-of: circleguard` | El PodMonitor y Alloy eligen los pods por ella |
| Etiqueta `app.kubernetes.io/name: <servicio>-service` (p. ej. `gateway-service`) | Nombre del servicio en métricas (`service`) y logs (`app`) |
| Puerto del contenedor llamado `http` | El PodMonitor lee las métricas de ese puerto |
| Deployment llamado `<servicio>-service` | Los paneles de memoria del contenedor filtran los pods por ese prefijo |
| Variable `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://jaeger-collector.observability:4318/v1/traces` en form, promotion y notification | Envía las trazas a Jaeger |

## Métricas

Además de las de Spring Boot (HTTP, JVM, CPU) y las de Resilience4j:

| Métrica | Servicio | Qué cuenta |
|---|---|---|
| `circleguard_checkins_total{result}` | gateway | Validaciones en el torniquete: `granted`, `denied` (riesgo de salud) e `invalid` (QR vencido o falso) |
| `circleguard_promotions_total{status}` | promotion | Cambios de estado aplicados (SUSPECT, PROBABLE, CONFIRMED...) |
| `circleguard_contacts_affected_total` | promotion | Contactos a los que se propagó un cambio de estado |

## Alertas críticas

| Alerta | Condición | Por qué importa |
|---|---|---|
| `CircleGuardServiceDown` | Un servicio no responde 1 minuto, o se quedó sin pods | Sin auth no hay login; sin gateway no se valida el ingreso |
| `CircleGuardHighErrorRate` | Más de 5 % de respuestas 5xx durante 5 minutos | Usuarios recibiendo errores |
| `CircleGuardHighLatencyP95` | p95 de latencia HTTP mayor a 1 s durante 5 minutos | El torniquete no puede tardar: hay fila en la entrada |

### Probar las alertas (evidencia)

Cada una se dispara a propósito y se captura el mensaje en Discord y la alerta
en Alertmanager. Después se deshace el cambio y llega el mensaje de resuelta.

1. **Servicio caído**: escalar gateway a 0 y esperar ~2 minutos.
   ```bash
   kubectl -n circleguard-dev scale deployment gateway-service --replicas=0
   # ...captura...
   kubectl -n circleguard-dev scale deployment gateway-service --replicas=1
   ```
2. **Errores altos**: pedir durante 6 minutos una ruta que responde 500. Con
   promotion caído, dashboard sigue respondiendo (circuit breaker), así que se
   usa una ruta de un servicio que falle de verdad, por ejemplo un login con
   identity escalado a 0 (auth autentica al usuario, no logra el ID anónimo
   después de los reintentos y responde 500). `USUARIO` y `CLAVE` son los de un
   usuario de prueba válido de dev:
   ```bash
   kubectl -n circleguard-dev scale deployment identity-service --replicas=0
   for i in $(seq 1 400); do curl -s -o /dev/null -X POST "$DEV_URL/svc/auth/api/v1/auth/login" \
     -H 'Content-Type: application/json' -d "{\"username\":\"$USUARIO\",\"password\":\"$CLAVE\"}"; sleep 1; done
   kubectl -n circleguard-dev scale deployment identity-service --replicas=1
   ```
3. **p95 alto**: generar carga con Locust (Darío, CG-100) por encima de lo que
   aguanta un pod, o bajar temporalmente el umbral a 0.1 s en `k8s/alerts.yaml`,
   aplicarlo, capturar y volver a 1 s.

## Logs y trazas

- En Grafana → *Explore* → Loki: `{namespace="circleguard-dev", app="promotion-service"}`.
- Por traza: `{namespace=~"circleguard-.*"} |= "<traceId>"`. Cada línea de
  Spring Boot lleva `[traceId-spanId]` y Grafana la enlaza a Jaeger (campo
  derivado `traceId`).
- En Jaeger, la traza de un cuestionario cruza
  `form: POST /api/v1/surveys` → `survey.submitted` → `promotion` →
  `promotion.status.changed` → `notification`.
