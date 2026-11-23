# CircleGuard · Informe final de la Parte 2

**Curso:** Ingeniería de Software V, Universidad Icesi · **Entrega:** domingo 22 de noviembre de 2026, 23:59 (America/Bogota)
**Equipo:** Luis Cadena (proceso, change management y documentación), Simón (pipeline de Jenkins), Darío (infraestructura y pruebas)

> Este informe se exporta a PDF. Cada afirmación remite a un documento del repo. No contiene secretos ni direcciones IP.

## Contenido

1. [Resumen ejecutivo](#1-resumen-ejecutivo)
2. [Entregables E2.1 a E2.10](#2-entregables-e21-a-e210)
3. [Respuesta a las observaciones del profesor](#3-respuesta-a-las-observaciones-del-profesor)
4. [Estado de la documentación](#4-estado-de-la-documentación)
5. [Lecciones aprendidas](#5-lecciones-aprendidas)
6. [Enlaces](#6-enlaces)

---

## 1. Resumen ejecutivo

El resumen ejecutivo en inglés (CG-110) está en [`executive-summary.md`](executive-summary.md); sus filas de resultados que dicen "[TBD]" se completan en este informe y en [`pruebas/informe-consolidado.md`](pruebas/informe-consolidado.md). A continuación, el resumen en español.

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
| Story points comprometidos y completados | 135 comprometidos (Darío 51, Simón 47, Luis 37); al cierre Jira registra 116 de 138 completados (84 %) y 33 de 43 historias en Listo | [`agil/sprint-2.md`](agil/sprint-2.md) §4.2 |
| PR integrados a `main` | 48 (#41 a #88), todos de 400 líneas o menos | [`agil/sprint-2.md`](agil/sprint-2.md) §4.2 |

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

Las secciones de los tres patrones nuevos de `patrones.md` ya están completas (propósito, ubicación, prueba y *trade-off*), a partir del código de los PR #72, #73 y #45.

### E2.5 · Observabilidad

El stack vive en el namespace `observability` (manifiestos y dashboards en [`observability/`](../observability)): métricas con Micrometer y Prometheus, dashboards de Grafana (técnico del gateway y de negocio), trazas distribuidas en Jaeger (traza form → Kafka → promotion), logs en Loki con `traceId`, y Alertmanager con 3 alertas críticas enviadas a Discord.

Las 3 alertas se dispararon y se resolvieron en `circleguard-dev`: `CircleGuardServiceDown` (identity sin réplicas), `CircleGuardHighLatencyP95` (auth, p95 de 8,4 s frente a un umbral de 1 s) y `CircleGuardHighErrorRate` (auth, 42 % de 5xx frente a un umbral de 5 %). Capturas en [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §7 y §10; qué hacer ante cada alerta, en [`manual-operaciones.md`](manual-operaciones.md) §6.

### E2.6 · Seguridad

- **Escaneo continuo:** Trivy en cada build; ninguna de las 9 imágenes tuvo CRITICAL (HIGH entre 16 y 55 por imagen, 220 en total). El umbral solo bloquea CRITICAL con arreglo, así que los 212 HIGH con arreglo pasan: es una decisión de umbral, no ausencia de riesgo ([`informe-consolidado.md`](pruebas/informe-consolidado.md) §6).
- **Secretos:** generados por Terraform en Key Vault y copiados al clúster con `create-secrets.sh`; ninguno está en el repo ([`infra/secretos.md`](infra/secretos.md)).
- **RBAC:** Jenkins accede al clúster con una ServiceAccount y un RBAC acotado (`infra/k8s/jenkins-rbac.yaml`, `jenkins-access.sh`).
- **TLS:** Ingress de Traefik con certificados de cert-manager (`ClusterIssuer letsencrypt-prod`) en los tres ambientes.
- **Superficie web:** ZAP encontró un hallazgo alto (nginx publica su versión; detección por versión, confianza media) y alertas medias y bajas de cabeceras HTTP; la corrección propuesta (`server_tokens off;`, cabeceras de endurecimiento e imagen base actualizada) queda registrada como mejora; no se aplicó por el congelamiento del 22/11.

### E2.7 · Pruebas avanzadas

- **Locust** ([`tests/performance`](../tests/performance)) con escenarios de validación de acceso y envío de cuestionarios, y objetivo declarado antes de correr (p95 < 500 ms, errores < 1 % en carga normal y < 5 % en estrés). Ambos se cumplieron. El cuello de botella fue el login de auth-service: en estrés, 116 de 316 logins fallaron (timeouts 504 y 401). Análisis: [`ANALISIS.md`](../tests/performance/results/ANALISIS.md).
- **ZAP baseline** sobre dev en el pipeline, con reglas en `tests/security-zap/zap-baseline.conf`; 0 FAIL, el reporte está versionado en [`evidencia/sprint-2/zap`](evidencia/sprint-2/zap/zap-baseline.html).
- **E2E con Playwright** (login, cuestionario y acceso al campus): [`pruebas/e2e.md`](pruebas/e2e.md). **Cobertura** con JaCoCo: [`pruebas/cobertura.md`](pruebas/cobertura.md).
- **Informe consolidado (CG-111):** [`pruebas/informe-consolidado.md`](pruebas/informe-consolidado.md) interpreta JaCoCo, Locust, ZAP y Trivy y propone 4 mejoras: escalar y proteger el login de auth-service; medir el rendimiento por endpoint (el agregado de 160 ms ocultó que login y QR fallan); endurecer `web` y subir el umbral de Trivy a HIGH; y medir ramas y servicios fuera del núcleo.
- **SonarQube:** la etapa *SonarQube + Quality Gate* pasó en los builds #2 y #3 de `main` ([`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §3); el PR #26 muestra que un Quality Gate fallido detiene el pipeline ([`agil/sprint-2.md`](agil/sprint-2.md) §4.4). El detalle por servicio (issues, duplicación y cobertura) se consulta en SonarQube; en el repo queda como evidencia el resultado del Quality Gate en el Stage View.
- **Cobertura:** el build exige ≥ 60 % en los servicios núcleo y falla si no se cumple (desde el PR #15); como los builds #2 y #3 pasaron, todos cumplen el umbral ([`agil/sprint-2.md`](agil/sprint-2.md) §4.4).

### E2.8 · *Change management* avanzado

La convención de versiones separa `vX.Y.Z-SNAPSHOT.<build>` (sin validar, solo dev y stage) de `vX.Y.Z` (release): tras la aprobación, `crane` copia la **misma imagen (mismo digest)** a la etiqueta de release, sin reconstruir. En `v1.2.0` se verificó el digest de las 9 imágenes antes y después. Detalle: [`versionado-y-releases.md`](change-management/versionado-y-releases.md); aprobación y solicitud de cambio: [`politica-de-aprobacion.md`](change-management/politica-de-aprobacion.md) y [`solicitud-de-cambio.md`](change-management/solicitud-de-cambio.md).

**Rollback ejecutado:** el job `circleguard-rollback` llevó prod de `v1.2.0` a `v1.1.0` con aprobación manual (el build #1 falló por ser la primera ejecución y el #2 corrió con parámetros). Limitación conocida: el rollback usa el chart de `main`, así que no revierte la configuración del chart (por ejemplo, el HPA de auth). Evidencia: [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §6; el diagrama está en [`arquitectura/uml/rollback-actividad.png`](arquitectura/uml/rollback-actividad.png).

**Compuertas en acción.** El build #1 de `main` falló en las E2E sobre dev por un CORS 403 y no llegó a producción; el PR #84 lo corrigió con 10 líneas en el nginx del chart ([`registro-cambios.md`](change-management/registro-cambios.md), [`agil/sprint-2.md`](agil/sprint-2.md) §2.2). `v1.1.0` llegó a prod con el build #2 (aprobó Darío) y `v1.2.0` con el build #3. El rollback (build #2 de `circleguard-rollback`, prod de `v1.2.0` a `v1.1.0`) lo lanzó Luis y lo aprobó Simón Reyes.

**Documentos:** plan de rollback en [`change-management/rollback.md`](change-management/rollback.md) y registro por ambiente en [`change-management/registro-cambios.md`](change-management/registro-cambios.md). Hay releases publicadas `v1.1.0` y `v1.2.0`; las release notes las genera git-cliff en la etapa *Tag + release notes* (`jenkins/vars/cgRelease.groovy`) y se publican en GitHub Releases (`v1.0.0` a `v1.2.0`); no se versiona un `CHANGELOG.md` en la raíz.

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

**Costos.** [`infra/costos.md`](infra/costos.md) estima ≈ USD 18,5 para las 4 VMs de la Parte 1 con 40 horas encendidas (precios por verificar en la calculadora de Azure). La suscripción original (Azure for Students) se quedó sin crédito el 15/11 a las 16:00 y las VMs se apagaron solas; Darío reconstruyó todo con Terraform en una cuenta nueva (un nodo E4as_v5 para k3s y una VM B4s_v2 de herramientas), con un costo estimado de USD 10 a 11 al día y alertas de presupuesto al 50, 80 y 100 % ([`agil/sprint-2.md`](agil/sprint-2.md) §4.5). **Costo real:** no se pudo registrar, porque Cost Management no mostraba datos al cierre al ser una suscripción nueva; es una limitación del servicio, no una omisión.

**Metodología ágil.** [`agil/sprint-2.md`](agil/sprint-2.md) ya incluye review, retrospectiva, comparación con el Sprint 1, burndown ([`evidencia/sprint-2/burndown-sprint-2.png`](evidencia/sprint-2/burndown-sprint-2.png)) y métricas. Dato a tener presente: el tablero medía con el campo "Story Points" pero las historias tenían los puntos en "Story point estimate", por eso el burndown se cambió a recuento de incidencias; al cierre las 10 historias abiertas son de Luis y todas de documentación, con el PR en verde esperando aprobación.

### E2.10 · Presentación

El esqueleto y el guion de 20 minutos, con tiempos por persona (Luis 6 min, Darío 8, Simón 6), diapositiva de respuesta a las observaciones del profesor, preguntas probables y lista de verificación, están en [`presentacion-parte-2.md`](presentacion-parte-2.md). Las capturas que usa están en `docs/evidencia/sprint-2/`; el guion se ajusta en el ensayo previo a la sustentación.

---

## 3. Respuesta a las observaciones del profesor

| Observación (Parte 1) | Respuesta en la Parte 2 | Evidencia |
|---|---|---|
| **Tamaño de los PR.** Un PR de ~20 000 líneas no se puede revisar. | Regla automática `pr-size` en GitHub Actions (etiquetas `size/S` a `size/XL`, falla sobre 400 líneas salvo `size/exception` con justificación), documentada en `branching.md` y en la plantilla de PR. | PR #49 (fusionado el 22/11) y 48 PR integrados con la regla activa, todos de 400 líneas o menos ([`agil/sprint-2.md`](agil/sprint-2.md) §4.2); regla en [`agil/branching.md`](agil/branching.md). `pr-size` es check obligatorio del ruleset "Proteger main" y el PR de prueba #95 (500 líneas, cerrado sin fusionar) muestra el bloqueo (ver `evidencia/sprint-2/pr-size-bloqueo.png`). |
| **Nomenclatura de versiones.** Distinguir una versión sin validar de una validada. | `vX.Y.Z-SNAPSHOT.<build>` frente a `vX.Y.Z`; la release es la misma imagen re-etiquetada. El build #1 (`v1.1.0-SNAPSHOT.1`) falló en E2E y nunca generó release, lo que evidencia la regla. | [`versionado-y-releases.md`](change-management/versionado-y-releases.md), [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §1 y §2. |

---

## 4. Estado de la documentación

Estado de los documentos que este informe necesita, al cierre del 22/11:

| Documento | Estado |
|---|---|
| [`executive-summary.md`](executive-summary.md) (CG-110) | Existe; conserva filas "[TBD]" de resultados que este informe resuelve |
| [`pruebas/informe-consolidado.md`](pruebas/informe-consolidado.md) (CG-111) | Existe; su §9 registra lo que queda para el siguiente sprint (detalle de Sonar, re-medición de JaCoCo y perfilado de auth) |
| [`change-management/rollback.md`](change-management/rollback.md) (CG-104) y [`registro-cambios.md`](change-management/registro-cambios.md) (CG-105) | Existen; el registro conserva campos por confirmar en Jenkins (hora del build #1, aprobador del build #3) |
| Release notes (CG-103) | En GitHub Releases (`v1.1.0`, `v1.2.0`), generadas por git-cliff |
| Circuit Breaker, Retry y Feature Toggle en [`patrones.md`](patrones.md) | Completos (PR #72, #73 y #45) |
| Review, retrospectiva y burndown en [`agil/sprint-2.md`](agil/sprint-2.md) | Completos |
| Costo real en [`infra/costos.md`](infra/costos.md) | Sin cifra real: Cost Management sin datos en la cuenta nueva (ver E2.9) |
| [`presentacion-parte-2.md`](presentacion-parte-2.md) (CG-113) | Guion listo; se ajusta en el ensayo |

---

## 5. Lecciones aprendidas

La retrospectiva completa y la comparación con el Sprint 1 están en [`agil/sprint-2.md`](agil/sprint-2.md) §3. Estas lecciones salen de hechos documentados en el repo:

1. **Un control solo se confía si se probó con un caso que debe fallar.** El build #1 falló en E2E (6 de 9 pruebas con login 403) y la versión con el error no pasó de dev; el SNAPSHOT evitó que se publicara una release rota ([`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) §8). El PR #26 hizo lo mismo con el Quality Gate.
2. **El ambiente real revela lo que el local no muestra.** El error de CORS apareció solo porque Traefik termina el TLS y el servicio ve http; se corrigió en el nginx del chart (PR #84).
3. **La prueba de estrés generó una mejora concreta.** Locust señaló a auth-service como cuello de botella y la respuesta fue un HPA, publicado como `v1.2.0` ([`ANALISIS.md`](../tests/performance/results/ANALISIS.md)).
4. **Verificar antes de afirmar.** El inventario de patrones descartó que `gateway-service` fuera un API Gateway y que Redis fuera *cache-aside*; documentar lo "no verificado" evita defender algo falso.
5. **La misma imagen de QA llega a producción.** Re-etiquetar con el mismo digest garantiza que lo probado es lo que corre en prod.
6. **El rollback tiene límites.** Revierte imágenes, no la configuración del chart ni el esquema de la base; hay que decirlo en la sustentación.
7. **Los hitos se atrasaron.** `sprint-2.md` registra que H0 a H4 se replanificaron al 21 y 22 de noviembre respecto al plan original (17 al 21). Con una entrega el domingo a las 23:59, la documentación final quedó comprimida en horas. Frente al Sprint 1 mejoraron los puntos (compromiso medible), los hitos con responsable, el tamaño de los PR (48 PR de ≤ 400 líneas) y las versiones; no mejoró el ritmo (trabajo concentrado en el último día) ni los fallos de Jenkins por carga ([`agil/sprint-2.md`](agil/sprint-2.md) §3.4).
8. **Medir con el campo correcto.** El tablero de Jira medía "Story Points" y las historias usaban "Story point estimate"; el burndown se pasó a recuento de incidencias. Acción: configurar el campo de estimación al crear el tablero.
9. **Los umbrales son decisiones.** Quedaron registradas, sin aplicar por el congelamiento, `server_tokens off;` en nginx (alerta High de ZAP) y subir el umbral de Trivy a HIGH (212 HIGH con arreglo).
10. **La infraestructura como código permitió reconstruir.** Al agotarse el crédito de Azure el 15/11, Darío recreó todo en una cuenta nueva solo con Terraform.

---

## 6. Enlaces

| Recurso | Enlace |
|---|---|
| Repositorio | <https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286> |
| Releases | [`v1.1.0`](https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286/releases/tag/v1.1.0) y [`v1.2.0`](https://github.com/ingesoft5/circle-guard-finalproject-A00395967-A00400880-A00401286/releases/tag/v1.2.0) |
| Jenkins | `<url-jenkins>` (job `circleguard/main`, builds #2 y #3; job `circleguard-rollback`, build #2) |
| Jira (proyecto `CG`, tablero) | <https://luisfeicesi.atlassian.net/jira/software/projects/CG/boards/2> |
| Burndown | [`evidencia/sprint-2/burndown-sprint-2.png`](evidencia/sprint-2/burndown-sprint-2.png) |
| Evidencia del Sprint 2 | [`evidencia/sprint-2/README.md`](evidencia/sprint-2/README.md) |
| Código de infraestructura | [`infra/terraform`](../infra/terraform), [`deploy/helm`](../deploy/helm), [`observability`](../observability) |

**Registro de uso de IA** ([`IAG_REGISTRO.md`](../IAG_REGISTRO.md)): la entrada de Luis para la Parte 2 está en el archivo; la de Darío existe (sesión 3, PR #79); la sección de Simón cubre hasta el 8 de noviembre y él agrega la entrada de su trabajo del Sprint 2.
