# CircleGuard · Informe final de la Parte 2

**Curso:** Ingeniería de Software V, Universidad Icesi · **Entrega:** domingo 22 de noviembre de 2026, 23:59 (America/Bogota)
**Equipo:** Luis Cadena (proceso, change management y documentación), Simón (pipeline de Jenkins), Darío (infraestructura y pruebas)

> Este informe se exporta a PDF. Cada afirmación remite a un documento del repo; lo que no tiene evidencia se marca como **pendiente de evidencia**. No contiene secretos ni direcciones IP.

## Contenido

1. [Resumen ejecutivo](#1-resumen-ejecutivo)
2. [Entregables E2.1 a E2.10](#2-entregables-e21-a-e210)
3. [Respuesta a las observaciones del profesor](#3-respuesta-a-las-observaciones-del-profesor)
4. [Estado de la documentación](#4-estado-de-la-documentación)
5. [Lecciones aprendidas](#5-lecciones-aprendidas)
6. [Enlaces](#6-enlaces)

---

## 1. Resumen ejecutivo

El resumen ejecutivo en inglés (CG-110) **no existe todavía en el repo** (`docs/executive-summary.md` no está en esta rama): **pendiente de evidencia**. Mientras tanto, este es el resumen en español.

**Problema.** En la Parte 1, CircleGuard (8 microservicios Spring Boot y un cliente web) corría con Docker Compose en 4 VMs de Azure, con infraestructura creada por scripts Bash ([`topologia.md`](infra/topologia.md)). Faltaba infraestructura reproducible, despliegue declarativo, seguridad continua, observabilidad y control sobre el tamaño de los cambios y las versiones.

**Solución.** Los servicios y el middleware pasaron a un clúster k3s en Azure, creado con Terraform y desplegado con un chart de Helm en tres namespaces (`circleguard-dev`, `circleguard-stage`, `circleguard-prod`). El pipeline de Jenkins compila, prueba, escanea con Trivy, despliega, ejecuta E2E y ZAP, promueve con aprobación manual a producción y notifica por Discord ([`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md)).

**Resultados clave** (todos de los documentos enlazados):

| Resultado | Valor | Fuente |
|---|---|---|
| Cobertura unitaria en los 5 servicios núcleo (meta ≥ 60 %) | auth 98,1 %, identity 94,1 %, promotion 81,4 %, form 97,7 %, gateway 88,9 % | [`pruebas/cobertura.md`](pruebas/cobertura.md) |
| E2E en el build de `v1.1.0` | 9 de 9 | [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §8 |
| Carga normal (30 usuarios, 3 min) | p95 160 ms, 0 % de errores | [`tests/performance/results/ANALISIS.md`](../tests/performance/results/ANALISIS.md) |
| Estrés (200 usuarios, 5 min) | p95 160 ms, 0,55 % de errores, el HPA escaló a 2 réplicas | ídem |
| Trivy (9 imágenes, build #3) | 0 CRITICAL; 220 HIGH (212 con arreglo) que no bloquean | [`pruebas/informe-consolidado.md`](pruebas/informe-consolidado.md) §6 |
| ZAP baseline sobre dev (build #3) | 0 reglas FAIL; 14 tipos de alerta (1 High, 2 Medium, 9 Low, 2 Info) | [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §5 y [`pruebas/informe-consolidado.md`](pruebas/informe-consolidado.md) §5 |
| Releases publicadas en producción | `v1.1.0` y `v1.2.0`, más un rollback de `v1.2.0` a `v1.1.0` | [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §1 y §6 |
| Story points comprometidos | 135 (Darío 51, Simón 47, Luis 37) | [`agil/sprint-2.md`](agil/sprint-2.md) |

---

## 2. Entregables E2.1 a E2.10

### E2.1 · Infraestructura como código con Terraform

La infraestructura está declarada en [`infra/terraform`](../infra/terraform) con seis módulos reutilizables (`network`, `cluster`, `registry`, `secrets`, `storage`, `observability`) y cuatro raíces: `envs/shared` (plataforma común: clúster k3s, VM de herramientas, presupuesto y alerta) y `envs/dev`, `envs/stage` y `envs/prod`, que solo llaman a los módulos y cambian valores. El estado vive en un *backend* remoto en una Storage Account creada una sola vez por `bootstrap/state.sh`.

Cada ambiente crea su Key Vault con 7 secretos generados por Terraform, y un almacenamiento de respaldos. Jenkins hace `terraform plan` en el PR y `apply` en `main` (producción con aprobación); en los builds #2 y #3 el plan dio "No changes" en los tres ambientes.

Detalle: [`infra/terraform/README.md`](../infra/terraform/README.md), [`arquitectura/terraform.md`](arquitectura/terraform.md), [`infra/topologia.md`](infra/topologia.md), [`infra/secretos.md`](infra/secretos.md).

### E2.2 · Orquestación en Kubernetes

Un solo chart de Helm ([`deploy/helm/circleguard`](../deploy/helm/circleguard)) despliega los servicios Java, el cliente web y el middleware, con un `values-<env>.yaml` por ambiente. Incluye ConfigMap común, Secret `circleguard-secrets` (creado desde Key Vault con `create-secrets.sh`, nunca versionado), Ingress de Traefik con TLS de cert-manager, sondas liveness/readiness de Spring Actuator y HPA en gateway, form y promotion. El HPA de auth-service se añadió en `v1.2.0` por la recomendación de la prueba de estrés.

Los pods corren con usuario `10001`. El pipeline despliega con `helm upgrade --install --atomic --wait`: si un pod no queda listo, Helm deshace el cambio.

Detalle: [`deploy/helm/circleguard/README.md`](../deploy/helm/circleguard/README.md), [`arquitectura/kubernetes.md`](arquitectura/kubernetes.md), [`arquitectura/servicios.md`](arquitectura/servicios.md).

### E2.3 · Pipeline incrementado

El pipeline de `main` recorre: compilación, pruebas unitarias e integración, SonarQube con Quality Gate bloqueante, Trivy (imágenes e IaC), Terraform (validate y plan por ambiente), publicación SNAPSHOT en Nexus, despliegue a dev con Helm, E2E, ZAP, despliegue a stage, aprobación manual, promoción a release, tag de Git y despliegue a prod. Trivy detiene el build ante vulnerabilidades CRITICAL con arreglo disponible; las 3 excepciones aceptadas están en [`riesgos-aceptados.md`](change-management/riesgos-aceptados.md). Los fallos, builds inestables o abortados y las releases a prod se notifican por Discord.

Evidencia: Stage View de los builds #2 y #3 y reportes de Trivy en [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §2 y §4. Diagrama: [`arquitectura/pipeline.md`](arquitectura/pipeline.md). El `Jenkinsfile` y las librerías son de Simón (carpeta `jenkins/`).

### E2.4 · Patrones de diseño

[`patrones.md`](patrones.md) inventaría los patrones existentes, **verificados en el código**: base de datos por servicio, arquitectura dirigida por eventos (form → Kafka → promotion/notification), máquina de estados de salud, vista materializada en Redis, *cache-aside* (parcial: solo se llama desde pruebas), enrutamiento en nginx (parcial) y endpoint de salud. Registra también lo que **no** se pudo afirmar, por ejemplo que `gateway-service` no es un API Gateway sino el validador de QR de los torniquetes.

Tres patrones nuevos:

- **Circuit Breaker** (dashboard → promotion, `PromotionClient`) y **Retry con backoff exponencial** (auth → identity, `IdentityClient`), con Resilience4j y umbrales en `application.yml`; se verifican con falla inyectada en `PromotionClientCircuitBreakerTest` e `IdentityClientRetryTest`. Detalle y *trade-offs*: [`patrones/resiliencia.md`](patrones/resiliencia.md).
- **Feature Toggle**: `circleguard.features.strict-gate.enabled` en el gateway (por defecto `false`; variable `CIRCLEGUARD_FEATURES_STRICT_GATE_ENABLED`). Encendido, el torniquete niega el ingreso si no hay estado en Redis. Tres casos en `QrValidationServiceTest`. Detalle y *trade-offs* (deuda de toggles viejos, combinaciones sin probar): [`patrones-feature-toggle.md`](patrones-feature-toggle.md).

Nota: las secciones de los tres patrones nuevos en `patrones.md` aún muestran "pendiente"; el contenido real está en los dos documentos anteriores (ver §4).

### E2.5 · Observabilidad

El stack vive en el namespace `observability` (manifiestos y dashboards en [`observability/`](../observability)): métricas con Micrometer y Prometheus, dashboards de Grafana (técnico del gateway y de negocio), trazas distribuidas en Jaeger (traza form → Kafka → promotion), logs en Loki con `traceId`, y Alertmanager con 3 alertas críticas enviadas a Discord.

Las 3 alertas se dispararon y se resolvieron en `circleguard-dev`: `CircleGuardServiceDown` (identity sin réplicas), `CircleGuardHighLatencyP95` (auth, p95 de 8,4 s frente a un umbral de 1 s) y `CircleGuardHighErrorRate` (auth, 42 % de 5xx frente a un umbral de 5 %). Capturas en [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §7 y §10; qué hacer ante cada alerta, en [`manual-operaciones.md`](manual-operaciones.md) §6.

### E2.6 · Seguridad

- **Escaneo continuo:** Trivy en cada build; ninguna de las 9 imágenes tuvo CRITICAL (HIGH entre 16 y 55 por imagen, 220 en total). El umbral solo bloquea CRITICAL con arreglo, así que los 212 HIGH con arreglo pasan: es una decisión de umbral, no ausencia de riesgo ([`informe-consolidado.md`](pruebas/informe-consolidado.md) §6).
- **Secretos:** generados por Terraform en Key Vault y copiados al clúster con `create-secrets.sh`; ninguno está en el repo ([`infra/secretos.md`](infra/secretos.md)).
- **RBAC:** Jenkins accede al clúster con una ServiceAccount y un RBAC acotado (`infra/k8s/jenkins-rbac.yaml`, `jenkins-access.sh`).
- **TLS:** Ingress de Traefik con certificados de cert-manager (`ClusterIssuer letsencrypt-prod`) en los tres ambientes.
- **Superficie web:** ZAP encontró un hallazgo alto (nginx publica su versión; detección por versión, confianza media) y alertas medias y bajas de cabeceras HTTP; la corrección propuesta (`server_tokens off;`, cabeceras de endurecimiento e imagen base actualizada) queda **pendiente**.

### E2.7 · Pruebas avanzadas

- **Locust** ([`tests/performance`](../tests/performance)) con escenarios de validación de acceso y envío de cuestionarios, y objetivo declarado antes de correr (p95 < 500 ms, errores < 1 % en carga normal y < 5 % en estrés). Ambos se cumplieron. El cuello de botella fue el login de auth-service: en estrés, 116 de 316 logins fallaron (timeouts 504 y 401). Análisis: [`ANALISIS.md`](../tests/performance/results/ANALISIS.md).
- **ZAP baseline** sobre dev en el pipeline, con reglas en `tests/security-zap/zap-baseline.conf`; 0 FAIL, el reporte está versionado en [`evidencia/sprint-2/zap`](evidencia/sprint-2/zap/zap-baseline.html).
- **E2E con Playwright** (login, cuestionario y acceso al campus): [`pruebas/e2e.md`](pruebas/e2e.md). **Cobertura** con JaCoCo: [`pruebas/cobertura.md`](pruebas/cobertura.md).
- **Informe consolidado (CG-111):** [`pruebas/informe-consolidado.md`](pruebas/informe-consolidado.md) interpreta JaCoCo, Locust, ZAP y Trivy y propone 4 mejoras: escalar y proteger el login de auth-service; medir el rendimiento por endpoint (el agregado de 160 ms ocultó que login y QR fallan); endurecer `web` y subir el umbral de Trivy a HIGH; y medir ramas y servicios fuera del núcleo.
- **SonarQube:** el informe consolidado marca el reporte de Sonar como **pendiente de evidencia**; solo consta que el Quality Gate pasó en los builds #2 y #3 ([`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §2).

### E2.8 · *Change management* avanzado

La convención de versiones separa `vX.Y.Z-SNAPSHOT.<build>` (sin validar, solo dev y stage) de `vX.Y.Z` (release): tras la aprobación, `crane` copia la **misma imagen (mismo digest)** a la etiqueta de release, sin reconstruir. En `v1.2.0` se verificó el digest de las 9 imágenes antes y después. Detalle: [`versionado-y-releases.md`](change-management/versionado-y-releases.md); aprobación y solicitud de cambio: [`politica-de-aprobacion.md`](change-management/politica-de-aprobacion.md) y [`solicitud-de-cambio.md`](change-management/solicitud-de-cambio.md).

**Rollback ejecutado:** el job `circleguard-rollback` llevó prod de `v1.2.0` a `v1.1.0` con aprobación manual (el build #1 falló por ser la primera ejecución y el #2 corrió con parámetros). Limitación conocida: el rollback usa el chart de `main`, así que no revierte la configuración del chart (por ejemplo, el HPA de auth). Evidencia: [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §6; el diagrama está en [`arquitectura/uml/rollback-actividad.png`](arquitectura/uml/rollback-actividad.png).

**Pendientes de evidencia:** el plan escrito `docs/change-management/rollback.md` y el registro `docs/change-management/registro-cambios.md` **no existen en esta rama**. La tabla de versiones de la evidencia del Sprint 2 (§1) cubre provisionalmente el registro por ambiente. Releases en GitHub confirmadas por el README de evidencia: `v1.1.0` y `v1.2.0`; `CHANGELOG.md` **no existe en la raíz** (pendiente).

### E2.9 · Documentación completa

| Tema | Documento |
|---|---|
| Arquitectura y diagramas | [`arquitectura/`](arquitectura) (`kubernetes.md`, `terraform.md`, `pipeline.md`, `servicios.md`, `uml/*.png`) |
| Metodología ágil | [`agil/sprint-2.md`](agil/sprint-2.md), [`agil/sprint-1.md`](agil/sprint-1.md), [`agil/branching.md`](agil/branching.md) |
| Patrones | [`patrones.md`](patrones.md) |
| Infraestructura | [`infra/topologia.md`](infra/topologia.md), [`infra/secretos.md`](infra/secretos.md) |
| Pruebas | [`pruebas/cobertura.md`](pruebas/cobertura.md), [`pruebas/e2e.md`](pruebas/e2e.md), `tests/performance/results/ANALISIS.md` |
| Costos | [`infra/costos.md`](infra/costos.md) |
| Operación | [`manual-operaciones.md`](manual-operaciones.md) |

**Costos.** La estimación actual es de ≈ USD 18,5 para las 4 VMs de la Parte 1 con 40 horas encendidas, más ≈ USD 3 por IPs y discos si las VMs se apagan durante un sprint de 6 días; todos los precios están marcados "verificar en la calculadora de Azure". Los recursos del clúster k3s y el **costo real** de Cost Management siguen **pendientes de evidencia** en [`infra/costos.md`](infra/costos.md).

**Metodología ágil.** `sprint-2.md` contiene la planeación (objetivo, reparto de 135 puntos, hitos). Su *review*, retrospectiva, burndown y métricas finales están marcados como pendientes: **pendiente de evidencia** hasta que se exporte el burndown de Jira.

### E2.10 · Presentación

El esqueleto y el guion de 20 minutos, con tiempos por persona (Luis 6 min, Darío 8, Simón 6), diapositiva de respuesta a las observaciones del profesor, preguntas probables y lista de verificación, están en [`presentacion-parte-2.md`](presentacion-parte-2.md). Ese documento aún tiene marcas **[pendiente]** que se resuelven con las capturas y enlaces de la evidencia antes de la sustentación.

---

## 3. Respuesta a las observaciones del profesor

| Observación (Parte 1) | Respuesta en la Parte 2 | Evidencia |
|---|---|---|
| **Tamaño de los PR.** Un PR de ~20 000 líneas no se puede revisar. | Regla automática `pr-size` en GitHub Actions (etiquetas `size/S` a `size/XL`, falla sobre 400 líneas salvo `size/exception` con justificación), documentada en `branching.md` y en la plantilla de PR. | [`agil/branching.md`](agil/branching.md). Si `pr-size` ya es check obligatorio del ruleset y existe la captura del bloqueo: **pendiente de evidencia**. |
| **Nomenclatura de versiones.** Distinguir una versión sin validar de una validada. | `vX.Y.Z-SNAPSHOT.<build>` frente a `vX.Y.Z`; la release es la misma imagen re-etiquetada. El build #1 (`v1.1.0-SNAPSHOT.1`) falló en E2E y nunca generó release, lo que evidencia la regla. | [`versionado-y-releases.md`](change-management/versionado-y-releases.md), [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §1 y §2. |

---

## 4. Estado de la documentación

Documentos que este informe necesita y su estado en esta rama (se cita "pendiente de evidencia" donde corresponde):

| Documento esperado | Estado |
|---|---|
| `docs/executive-summary.md` (CG-110) | No existe |
| `docs/pruebas/informe-consolidado.md` (CG-111) | Existe; su §9 lista la evidencia pendiente (Sonar, JaCoCo final, perfilado de auth) |
| `docs/change-management/rollback.md` (CG-104) | No existe |
| `docs/change-management/registro-cambios.md` (CG-105) | No existe |
| `CHANGELOG.md` (CG-103) | No existe en la raíz |
| Secciones Circuit Breaker, Retry y Feature Toggle en `docs/patrones.md` | Siguen como "pendiente" aunque el contenido está en `patrones/resiliencia.md` y `patrones-feature-toggle.md` |
| Review, retrospectiva y burndown en `docs/agil/sprint-2.md` | Pendientes |
| Costo real en `docs/infra/costos.md` | Pendiente |

---

## 5. Lecciones aprendidas

La retrospectiva formal del Sprint 2 **no está escrita** (pendiente de evidencia). Estas lecciones salen de hechos documentados en el repo:

1. **Un control solo se confía si se probó con un caso que debe fallar.** El build #1 falló en E2E (6 de 9 pruebas con login 403) y la versión con el error no pasó de dev; el SNAPSHOT evitó que se publicara una release rota ([`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §8). El PR #26 hizo lo mismo con el Quality Gate.
2. **El ambiente real revela lo que el local no muestra.** El error de CORS apareció solo porque Traefik termina el TLS y el servicio ve http; se corrigió en el nginx del chart (PR #84).
3. **La prueba de estrés generó una mejora concreta.** Locust señaló a auth-service como cuello de botella y la respuesta fue un HPA, publicado como `v1.2.0` ([`ANALISIS.md`](../tests/performance/results/ANALISIS.md)).
4. **Verificar antes de afirmar.** El inventario de patrones descartó que `gateway-service` fuera un API Gateway y que Redis fuera *cache-aside*; documentar lo "no verificado" evita defender algo falso.
5. **La misma imagen de QA llega a producción.** Re-etiquetar con el mismo digest garantiza que lo probado es lo que corre en prod.
6. **El rollback tiene límites.** Revierte imágenes, no la configuración del chart ni el esquema de la base; hay que decirlo en la sustentación.
7. **Los hitos se atrasaron.** `sprint-2.md` registra que H0 a H4 se replanificaron al 21 y 22 de noviembre respecto al plan original (17 al 21). Con una entrega el domingo a las 23:59, la documentación final quedó comprimida en horas; la comparación formal con el Sprint 1 (puntos, hitos, PR pequeños) queda pendiente del burndown.

---

## 6. Enlaces

| Recurso | Enlace |
|---|---|
| Repositorio | <https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286> |
| Releases | [`v1.1.0`](https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286/releases/tag/v1.1.0) y [`v1.2.0`](https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286/releases/tag/v1.2.0) |
| Jenkins | `<url-jenkins>` (job `circleguard/main`, builds #2 y #3; job `circleguard-rollback`, build #2) |
| Jira (proyecto `CG`) | `<url-jira>` (la URL no está en el repo; pendiente de evidencia) |
| Evidencia del Sprint 2 | [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) |
| Código de infraestructura | [`infra/terraform`](../infra/terraform), [`deploy/helm`](../deploy/helm), [`observability`](../observability) |

**Registro de uso de IA:** cada integrante deja su entrada de la Parte 2 en [`IAG_REGISTRO.md`](../IAG_REGISTRO.md); Simón y Darío deben escribir la suya (pendiente).
