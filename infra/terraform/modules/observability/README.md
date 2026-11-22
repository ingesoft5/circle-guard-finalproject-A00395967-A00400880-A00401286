# Módulo `observability`

La parte de la observabilidad que vive **fuera** del clúster:

| Recurso | Para qué |
|---|---|
| Grupo de acción (correo) | Destino de las alertas de Azure |
| Presupuesto mensual | Avisa al 50, 80 y 100 % del gasto real y cuando el pronóstico pasa del 100 % (FinOps). La suscripción de la Parte 1 se apagó por quedarse sin crédito |
| Alerta `VmAvailabilityMetric` por VM | Avisa si el nodo k3s o tools se caen; Prometheus no puede avisar de su propia caída |

Prometheus, Grafana, Alertmanager, Loki y Jaeger van dentro del clúster, en el
namespace `observability`. Ese namespace lo crea el módulo `cluster`, y
Simón los instala con `observability/install.sh` (CG-93 a CG-97).

Los correos van en `terraform.tfvars`, que no se versiona; hay un ejemplo en
`terraform.tfvars.example`.
