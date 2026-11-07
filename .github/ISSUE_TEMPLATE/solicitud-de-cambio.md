---
name: Solicitud de Cambio (RFC)
about: Plantilla oficial para registrar y tramitar una solicitud de cambio (RFC) en CircleGuard
title: "[RFC]: "
labels: ["change-request"]
assignees: ""
---

# Plantilla de Solicitud de Cambio (RFC - Request for Change)

Esta plantilla constituye el formato oficial para proponer, documentar, evaluar y aprobar cualquier modificación sobre la infraestructura, servicios backend, frontend o configuraciones operativas de la plataforma **CircleGuard**.

---

## 1. Información General del Cambio

| Campo | Detalle |
|---|---|
| **ID de la Solicitud** | `RFC-YYYYMMDD-XXX` (ej. `RFC-20261107-001`) |
| **Título del Cambio** | Breve título descriptivo del cambio |
| **Solicitante** | Nombre completo, rol y usuario de GitHub / Jenkins |
| **Fecha de Solicitud** | YYYY-MM-DD |
| **Tipo de Cambio** | `[ ] Estándar` / `[ ] Normal` / `[ ] Emergencia` |
| **Prioridad** | `[ ] Baja` / `[ ] Media` / `[ ] Alta` / `[ ] Crítica` |
| **Rama Git / PR Asociado** | `<tipo>/CG-<n>-descripcion` / PR #XX |

---

## 2. Clasificación del Tipo de Cambio

- **Estándar**: cambio pre-aprobado, rutinario, de bajo riesgo y probado exhaustivamente (p. ej., actualización de documentación técnica, dependencias menores sin cambios de API, mejoras en pruebas automáticas).
- **Normal**: cambio que introduce nuevas funcionalidades, modifica contratos de API, actualiza esquemas de base de datos o impacta microservicios centrales. Requiere revisión por pares en PR y aprobación explícita en Jenkins para producción.
- **Emergencia**: intervención inmediata ante incidentes críticos que interrumpen la operación de torniquetes de acceso o la trazabilidad de contagios. Se implementa con celeridad y se regulariza su aprobación post-despliegue en un plazo no mayor a 24 horas.

---

## 3. Servicios y Componentes Afectados

Marque los componentes sobre los cuales recae el cambio:

- [ ] `auth-service` (Puerto 8180 - Autenticación, JWT, QR, LDAP)
- [ ] `identity-service` (Puerto 8083 - Vault de anonimización)
- [ ] `promotion-service` (Puerto 8088 - Motor epidemiológico, Grafos, Redis)
- [ ] `notification-service` (Puerto 8082 - Alertas, Correos, LMS)
- [ ] `form-service` (Puerto 8086 - Encuestas de salud, Kafka)
- [ ] `file-service` (Puerto 8085 - Carga de soportes médicos)
- [ ] `gateway-service` (Puerto 8087 - Validación física de torniquetes)
- [ ] `dashboard-service` (Puerto 8084 - Analítica y k-anonymity)
- [ ] `web` (Puerto 80 - Cliente Web Expo / React Native)
- [ ] **Middleware**: `[ ] PostgreSQL` / `[ ] Neo4j` / `[ ] Kafka` / `[ ] Redis` / `[ ] OpenLDAP`
- [ ] **Infraestructura CI/CD**: `[ ] Jenkinsfile` / `[ ] Shared Library` / `[ ] Dockerfiles`

---

## 4. Descripción y Justificación

### Descripción Técnica
*Detalle qué se va a modificar a nivel de código, dependencias, variables de entorno, esquemas de bases de datos o tópicos de mensajería.*

### Justificación del Negocio / Operativa
*Explique por qué es necesario este cambio, qué problema resuelve o qué valor aporta a la comunidad del campus.*

---

## 5. Análisis de Riesgo e Impacto

| Dimensión | Evaluación (`Bajo` / `Medio` / `Alto`) | Detalle y Mitigación |
|---|---|---|
| **Impacto en Disponibilidad** | | *¿Habrá indisponibilidad momentánea o degradación del servicio?* |
| **Riesgo en Datos / Esquema** | | *¿Modifica tablas en PostgreSQL o nodos/relaciones en Neo4j?* |
| **Impacto en Torniquetes** | | *¿Afecta la validación de acceso físico y lectura de QR?* |
| **Impacto en Privacidad** | | *¿Afecta el esquema de anonimización o el cálculo de k-anonymity?* |

---

## 6. Plan de Pruebas y Validación

Marque y describa las pruebas superadas antes de autorizar el despliegue a producción:

- [ ] **Pruebas Unitarias**: cobertura mínima del 60 % en servicios núcleo validada con JaCoCo (`cgUnitTest.groovy`).
- [ ] **Análisis Estático**: Quality Gate de SonarQube aprobado sin vulnerabilidades críticas (`cgSonar.groovy`).
- [ ] **Pruebas de Integración**: interacción con bases de datos y Kafka mediante Testcontainers (`cgIntegrationTest.groovy`).
- [ ] **Pruebas End-to-End (E2E)**: suite Playwright ejecutada sobre el ambiente `dev` (`cgE2E.groovy`).
- [ ] **Validación en Staging**: pruebas exploratorias y de humo en el ambiente `stage` con datos anónimos de prueba.

---

## 7. Plan de Rollback (Plan de Contingencia)

En caso de fallo en producción, regresión de rendimiento o anomalía funcional no detectada en staging, se procederá a ejecutar el plan de reversión inmediato.

### Procedimiento Automatizado de Rollback
1. **Mecanismo**: ejecución del job parametrizado en Jenkins configurado en `jenkins/Jenkinsfile.rollback`.
2. **Parámetros del Job**:
   - `TARGET_ENV`: Seleccionar `prod`.
   - `ROLLBACK_VERSION`: Versión SemVer inmutable previa conocida y estable (p. ej. `v1.0.0`), ya existente en el registro de imágenes Nexus.
   - `REASON`: Explicación detallada de la anomalía que motiva la reversión (ej. `Fallo de validación en torniquetes físicos reportado por gateway-service`).
3. **Aprobación de Reversión**: la etapa `Aprobación` en `Jenkinsfile.rollback` requiere confirmación expresa de un aprobador autorizado (`luis`, `simon` o `dario`).
4. **Despliegue de Imágenes Inmutables**: Jenkins invoca `cgDeploy(env: 'prod', version: env.VERSION)`, actualizando los contenedores sin recompilar código mediante `docker compose up -d`.
5. **Consideración de Base de Datos**: Flyway solo realiza migraciones hacia adelante; los cambios de esquema deben diseñarse bajo el patrón *expand/contract* para permitir compatibilidad hacia atrás con la versión previa.
6. **Tiempo Estimado de Recuperación (RTO)**: inferior a 3 minutos.

---

## 8. Aprobación y Ventana de Ejecución

### Ventana de Implementación
- **Fecha y Hora de Inicio**: YYYY-MM-DD HH:MM (Zona horaria local)
- **Fecha y Hora de Finalización**: YYYY-MM-DD HH:MM
- **Duración Estimada**: XX minutos

### Registro de Aprobaciones
| Rol | Nombre del Aprobador | Decisión (`Aprobado` / `Rechazado`) | Fecha y Registro |
|---|---|---|---|
| **Revisor de Código (Peer)** | | | PR #XX en GitHub |
| **Aprobador Producción (CAB)** | `luis` / `simon` / `dario` | | Input en Jenkins Build #XX |

---

## 9. Resultado de la Implementación (Post-Despliegue)

- [ ] **Exitosa**: cambio desplegado en producción, verificación post-lanzamiento (*smoke test*) satisfactoria y monitoreo de salud en `/actuator/health` en estado `UP`.
- [ ] **Fallida con Rollback**: se presentó anomalía y se ejecutó exitosamente el `Jenkinsfile.rollback` hacia la versión previa.
- [ ] **Fallida con Incidente**: se presentó anomalía que requiere intervención correctiva adicional.

**Observaciones y Lecciones Aprendidas**:
*Documentar hallazgos relevantes o acciones preventivas para futuros releases.*
