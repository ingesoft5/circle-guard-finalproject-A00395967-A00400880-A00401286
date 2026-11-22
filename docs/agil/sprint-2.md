# Sprint 2: Kubernetes, Convención de Versiones, Resiliencia y Documentación Final

**Periodo:** 17 al 22 de noviembre de 2026  
**Marco de trabajo:** Scrum adaptado para entregas de alta intensidad técnica  
**Proyecto:** CircleGuard (Monorepo Spring Boot 3.2 / Java 21 y React Native Expo)  
**Organización en GitHub:** `ingesoft5`  
**Proyecto Jira:** `CG`  
**Entrega final:** domingo 22 de noviembre de 2026, 23:59 (America/Bogota)

---

## 1. Planeación del Sprint 2

### 1.1 Objetivo del Sprint
Llevar los 8 microservicios y el cliente web desde Docker Compose (Parte 1) a un clúster Kubernetes (k3s) aprovisionado con Terraform, desplegado con Helm y alimentado por el pipeline de Jenkins hasta `circleguard-dev`. Además, cerrar las observaciones del profesor sobre la sustentación de la Parte 1: limitar el tamaño de los Pull Requests con una regla automática y separar con claridad las versiones sin validar (`SNAPSHOT`) de las releases (`vX.Y.Z`). Por último, incorporar tres patrones de diseño (Circuit Breaker, Retry y Feature Toggle), documentar la arquitectura y los costos, y producir el informe final, el resumen ejecutivo y la presentación.

### 1.2 Reparto por persona (Story Points)
La estimación de puntos proviene del tablero de Jira; el detalle por historia queda pendiente de evidencia hasta exportar el burndown.

| Integrante | Rol en el sprint | Story Points | Historias principales |
|---|---|:---:|---|
| **Darío** | Infraestructura y pruebas | **51** | Terraform para k3s (red, clúster, registro y almacenamiento), chart de Helm, namespaces, ConfigMap (CG-82), reportes de Locust y ZAP (H4), y limpieza de SNAPSHOT viejos en Nexus (CG-117). |
| **Simón** | Pipeline Jenkins | **47** | Convención SNAPSHOT en el pipeline (CG-116), despliegue a Kubernetes con Trivy y observabilidad, patrones Circuit Breaker y Retry, y reporte de Trivy (H4). |
| **Luis** | Coordinación, change management, empaquetado y documentación | **37** | Límite de tamaño de PR (CG-114), convención de versiones (CG-115), Dockerfile de file-service (CG-102), patrones y Feature Toggle (CG-100, CG-101), diagramas (CG-107), costos (CG-108), manual de operaciones (CG-109), rollback y registro de cambios (CG-104, CG-105), release notes (CG-103), informe de pruebas (CG-111), resumen ejecutivo (CG-110), informe final (CG-112) y presentación (CG-113). |

Total del sprint: **135 Story Points**.

### 1.3 Historias del Sprint (orden de ejecución)
La secuencia se organiza en fases según las dependencias entre integrantes:

- **Fase 0 (mar 17):** CG-115 (convención SNAPSHOT → release) y CG-114 (límite de tamaño de PR) en `main`, para que Simón y Darío trabajen con PR de 400 líneas o menos. Planeación del sprint (CG-106, parte 1).
- **Fase 1 (mié 18):** CG-102 (Dockerfile de file-service, para el chart de Darío), CG-100 (inventario de patrones existentes) y CG-101 (Feature Toggle).
- **Fase 2 (jue 19 – vie 20):** CG-107 (diagramas reales de Kubernetes y Terraform), CG-108 (estimación de costos) y CG-109 (manual de operaciones, borrador). Dependen del Terraform y del chart de Darío.
- **Fase 3 (vie 20 noche – sáb 21):** CG-104 (rollback en Kubernetes), CG-105 (registro de cambios por ambiente), CG-103 (release notes) y CG-108 (costo real). Dependen de H3.
- **Fase 4 (dom 22):** CG-111 (informe consolidado de pruebas, con datos de H4), CG-110 (resumen ejecutivo en inglés), CG-106 (parte 2: review y retrospectiva), CG-112 (informe PDF final) y CG-113 (presentación).

### 1.4 Hitos
Los hitos se replanificaron respecto al plan original para absorber el retraso del Sprint 2. Los horarios son hora de Colombia.

| Hito | Responsable | Fecha y hora | Qué desbloquea |
|---|---|---|---|
| **H0** | Luis | sáb 21 nov, 18:00 | CG-115 y CG-114 en `main`. Simón implementa la convención SNAPSHOT y todos trabajan con PR de 400 líneas o menos. |
| **H1** | Darío | sáb 21 nov, 22:00 | Clúster con namespaces. |
| **—** | Luis | mié 18 nov (original) | CG-102 (Dockerfile de file-service) para el chart de Darío. |
| **H2** | Darío | dom 22 nov, 10:00 | La app corre en `circleguard-dev`. Luis puede hacer los diagramas reales. |
| **H3** | Simón | dom 22 nov, 14:00 | El pipeline despliega a Kubernetes. Luis ejecuta el rollback. |
| **H4** | Simón y Darío | dom 22 nov, 16:00 | Reportes de Trivy, ZAP y Locust para el informe de Luis. |
| **Congelamiento** | Todos | dom 22 nov, 18:00 | Después solo documentación y arreglos. |

El avance de cada hito se revisa cada noche en el grupo del equipo. Si un hito se atrasa, se pide un plan B al agente de Luis en la siguiente sesión.

---

## 2. Sprint Review: Entregables y Trazabilidad de PRs

*Pendiente: se completa el domingo 22 de noviembre, después del congelamiento.*

---

## 3. Retrospectiva del Sprint 2

*Pendiente: se completa el domingo 22 de noviembre. Se comparará con la retrospectiva del Sprint 1 (sección 3 de `sprint-1.md`).*

---

## 4. Métricas del Sprint 2

*Pendiente: el burndown se exporta de Jira el domingo 22 de noviembre.*

| Métrica | Definición | Valor del Sprint 2 | Observaciones |
|---|---|:---:|---|
| **Story Points comprometidos** | Suma de puntos planeados por persona. | **135** (Darío 51, Simón 47, Luis 37) | Valor de planeación. |
| **Story Points completados** | Puntos de historias cerradas al final del sprint. | Pendiente de evidencia | Se toma de Jira el domingo 22. |
| **Número de PRs gestionados** | PR integrados a `main` durante el sprint. | Pendiente de evidencia | Se toma de `git log --oneline` el domingo 22. |
| **Hitos cumplidos (H0 a H4)** | Hitos entregados a tiempo sobre el total. | Pendiente de evidencia | Se revisa contra la tabla de la sección 1.4. |
