# Sprint 1: Pipeline CI/CD, Infraestructura en Azure, Pruebas y Release

**Periodo:** 5 al 8 de noviembre de 2026  
**Marco de trabajo:** Scrum adaptado para entregas de alta intensidad técnica  
**Proyecto:** CircleGuard (Monorepo Spring Boot 3.2 / Java 21 y React Native Expo)  
**Organización en GitHub:** `ingesoft5`  
**Proyecto Jira:** `CG` (66 issues organizados en 8 épicas)

---

## 1. Planeación del Sprint 1

### 1.1 Objetivo del Sprint
Implementar y desplegar el pipeline de integración continua (CI) y entrega continua (CD) automatizado en Jenkins, contenerizar de forma inmutable los 8 microservicios y el cliente web (sin etiqueta `latest`), aprovisionar la infraestructura multi-ambiente en Microsoft Azure mediante scripts Bash idempotentes, garantizar la suite de pruebas automatizadas (cobertura unitaria $\ge 60\%$ en servicios núcleo, pruebas de integración y E2E con Playwright), y automatizar la promoción de versiones (`dev` $\rightarrow$ `stage` $\rightarrow$ `prod`) con gobernanza de cambios y changelog generado por `git-cliff`.

### 1.2 Historias y Tareas Planeadas (Backlog del Sprint)
El trabajo se estructuró a partir de las 8 épicas definidas en el Sprint 0, distribuyendo las historias clave entre los tres integrantes:

- **E1.1 Gestión ágil y branching (Luis):**
  - CG-10: Documentación formal de la estrategia de branching basada en GitHub Flow (`docs/agil/branching.md`).
  - CG-12: Actualización de la plantilla de Pull Request (`.github/pull_request_template.md`).
  - CG-14 / CG-15: Registro de planeación, review, retrospectiva y métricas de Sprint 0 y Sprint 1.
- **E1.2 Infraestructura en la nube con Bash (Darío):**
  - CG-30: Definición de topología de infraestructura multi-región y dimensionamiento de VMs.
  - CG-31: Script de aprovisionamiento automatizado en Azure (`infra/azure/provision.sh`).
  - CG-32 / CG-33: Scripts de arranque idempotente para servidor de herramientas y ambientes (`bootstrap-tools.sh`, `bootstrap-env.sh`, `bootstrap-all.sh`).
  - CG-34: Validación y evidencias de idempotencia en segunda ejecución.
- **E1.3 Empaquetamiento inmutable (Luis):**
  - CG-16: Mapeo de dependencias de servicios, puertos y variables de entorno (`docs/arquitectura/servicios.md`).
  - CG-17: Creación de Dockerfiles multi-stage con usuario no root para los 8 microservicios Java 21.
  - CG-18: Exposición de endpoints de Actuator (`/actuator/health`) para healthchecks en compose.
  - CG-21: Dockerfile multi-stage para el cliente web Expo servido a través de Nginx como proxy inverso.
  - CG-23: Archivos `docker-compose` independientes para los ambientes `dev`, `stage` y `prod`.
- **E1.4 Pipeline Jenkins - CI (Simón):**
  - CG-48: Construcción de imagen Docker personalizada para Jenkins con CLI, plugins y herramientas.
  - CG-50: Configuración del Jenkinsfile multibranch activado por webhook de GitHub.
  - CG-51: Desarrollo de la Shared Library de Jenkins (`jenkins/vars/*.groovy`).
  - CG-52: Detección y compilación selectiva por microservicio cambiado (`cgChangedServices`).
  - CG-53: Ejecución de pruebas unitarias y generación de reportes JaCoCo (`cgUnitTest`).
  - CG-54: Quality Gate bloqueante con SonarQube (`cgSonar`).
  - CG-55: Ejecución de pruebas de integración con Testcontainers en el pipeline (`cgIntegrationTest`).
- **E1.5 Entrega continua y promoción (Simón):**
  - CG-56: Publicación de imágenes versionadas en Nexus (`cgDockerPublish`).
  - CG-57: Despliegue automatizado a ambiente `dev` vía SSH (`cgDeploy`).
  - CG-58: Promoción a ambiente `stage` condicionada al éxito de E2E.
  - CG-59: Compuerta manual de aprobación interactiva (`input`) para despliegue a `prod`.
  - CG-60: Notificaciones automáticas de estado a Discord (`cgNotify`).
  - CG-61: Pipeline de rollback automático redesplegando versiones estables (`jenkins/Jenkinsfile.rollback`).
- **E1.6 Pruebas automatizadas (Darío):**
  - CG-35 a CG-39: Pruebas unitarias para alcanzar $\ge 60\%$ de cobertura de líneas en servicios núcleo (`auth`, `identity`, `promotion`, `form`, `gateway`).
  - CG-40 a CG-43: Pruebas de integración con Testcontainers (Redis, Postgres, Kafka, Neo4j).
  - CG-44 a CG-47: Suite de pruebas End-to-End con Playwright (login, encuesta epidemiológica y validación de acceso QR).
- **E1.7 Change management y release notes (Luis):**
  - CG-25: Política de aprobación de cambios y plantilla de RFC (`docs/change-management/`).
  - CG-26: Configuración de `git-cliff` (`cliff.toml`) para notas de lanzamiento automáticas y cálculo SemVer.
- **E1.8 Documentación y arquitectura (Conjunta):**
  - CG-63: Diagramas de arquitectura (aplicación, infraestructura y flujo de entrega en `docs/arquitectura/arquitectura.md`).
  - CG-64: Diagrama de flujo de pipeline CI/CD (`docs/arquitectura/pipeline.md`).
  - CG-66: Registro de colaboración con inteligencia artificial generativa (`IAG_REGISTRO.md`).

---

## 2. Sprint Review: Entregables y Trazabilidad de PRs

Durante el Sprint 1 se completaron e integraron a la rama `main` los siguientes Pull Requests mediante Squash and Merge, verificables directamente en el historial de `git log --oneline`:

| PR # | Commit Hash | Tipo y Título Convencional | Responsable | Entregable Principal |
|:---:|:---:|---|:---:|---|
| **#1** | `3fad0e8` | `chore: ignore build outputs and secrets` | Luis | Configuración de `.gitignore` excluyendo artefactos de compilación, binarios y secretos. |
| **#2** | `0bdaf17` | `docs(arch): map service ports, dependencies and env vars` | Luis | Mapa completo de puertos, variables de entorno (*relaxed binding*) y dependencias middleware (`docs/arquitectura/servicios.md`). |
| **#3** | `12b83ed` | `ci(jenkins): add multibranch pipeline, shared library and rollback job` | Simón | Estructura base de `Jenkinsfile`, Shared Library en `jenkins/vars/*.groovy` y job de rollback. |
| **#4** | `cc0a1d3` | `test(gateway): add QR auth to gateway integration test with Redis` | Simón / Darío | Prueba de integración de validación criptográfica de tokens QR contra Redis real mediante Testcontainers. |
| **#5** | `5b1afef` | `test(e2e): set up playwright with junit and html reports` | Simón / Darío | Configuración base del framework de pruebas E2E con Playwright, TypeScript y reportería JUnit/HTML. |
| **#6** | `bd537dc` | `docs(arch): add CI/CD pipeline diagram and IAG log` | Simón | Documentación visual del pipeline CI/CD en Mermaid (`docs/arquitectura/pipeline.md`) y primer registro en `IAG_REGISTRO.md`. |
| **#7** | `9548f5c` | `feat(health): expose actuator readiness probes in all services` | Luis | Inclusión de Spring Boot Actuator en los microservicios con sondas de `/actuator/health` para los healthchecks de contenedores. |
| **#8** | `2f6671e` | `build(docker): add multi-stage non-root images for all services` | Luis | Creación de Dockerfiles multi-stage con OpenJDK 21 sobre Eclipse Temurin y ejecución con usuario no privilegiado `appuser`. |
| **#9** | `6954c80` | `feat(web): serve web client from nginx with same-origin api proxy` | Luis | Empaquetamiento del cliente web con `expo export` y servidor Nginx con enrutamiento de proxy inverso `/svc/*` para evitar problemas de CORS. |
| **#10** | `42b789a` | `docs(agile): document github flow branching strategy` | Luis | Documentación formal de la estrategia GitHub Flow, convención de nombres de rama y mapeo de Conventional Commits a SemVer (`docs/agil/branching.md`). |
| **#11** | `1212554` | `docs(change): add change management policy and git-cliff config` | Luis | Política de aprobación por tipo de cambio (estándar, normal, emergencia), plantilla de RFC en GitHub y configuración de `cliff.toml`. |
| **#12** | `a656c99` | `fix(test): repair unit tests that broke the pipeline` | Darío | Corrección de fallos en pruebas unitarias preexistentes que causaban quiebres en las ejecuciones de CI. |
| **#13** | `1ea58b6` | `test(e2e): add login, health questionnaire and campus access flows` | Darío | Implementación de los 3 flujos E2E críticos: autenticación, llenado de encuesta de salud diaria y validación en torniquete. |
| **#14** | `d5ce8a8` | `fix(jenkins): fetch target branch to detect changed services in PRs` | Simón | Ajuste en `cgChangedServices` para descargar la rama destino (`origin/main`) antes de calcular el `git diff`, habilitando compilación selectiva real en PRs. |
| **#15** | `37aa103` | `test: raise core services coverage above 60% and enforce it in the build` | Darío | Fortalecimiento de pruebas unitarias para superar el 60 % de cobertura de líneas en los 5 servicios núcleo (`auth`, `identity`, `promotion`, `form`, `gateway`). |
| **#18** | `0e69114` | `docs(arch): document how the pipeline selects services to build` | Simón | Documentación técnica de las reglas de ejecución selectiva y escenarios de build (`docs/arquitectura/pipeline.md`). |
| **#19** | `746ed48` | `ci(jenkins): enforce 60% line coverage on core services` | Simón | Configuración estricta en el pipeline ejecutando la tarea Gradle `jacocoTestCoverageVerification` como puerta de calidad obligatoria. |
| **#21** | `d349392` | `docs(iag): log second session of CI/CD pipeline work` | Simón | Registro detallado de la segunda sesión de trabajo con IA (lecciones aprendidas, resolución de fallos y ajustes de configuración). |
| **#22** | *(Rama)* | `feature/CG-31-bootstrap-infra` (`9493b88`) | Darío | Scripts Bash idempotentes (`provision.sh`, `bootstrap-all.sh`), topología en Azure, configuración de NSGs, Nexus y convergencia del Sonar Quality Gate. |

---

## 3. Retrospectiva del Sprint 1

La retrospectiva analizó objetivamente las dinámicas del equipo durante el sprint, capturando tanto los aciertos técnicos como las fricciones reales observadas en el repositorio.

### 3.1 Qué funcionó bien (Aciertos)
- **Trabajo en paralelo mediante Git Worktrees y agentes de IA:** Para evitar pisarse el trabajo en el mismo directorio local y permitir que Luis, Simón y Darío avanzaran simultáneamente en empaquetamiento, pipeline e infraestructura, se emplearon Git Worktrees (`.worktrees/*`, `.claude/worktrees/*`). Esto eliminó bloqueos por ramas locales y permitió que cada agente de IA (Claude Code, Antigravity CLI) operara en su propio contexto aislado.
- **Estrategia "Build Once, Deploy Everywhere":** Al empaquetar una sola vez las imágenes inmutables etiquetadas con la versión SemVer y publicarlas en Nexus (`20.230.211.66:8082`), los despliegues a `dev`, `stage` y `prod` se realizaron de manera homogénea y predecible mediante `docker-compose pull` y `up -d`.
- **Ejecución selectiva de compilación en PRs:** Tras ajustar la comparación contra `origin/main` (PR #14), los PRs que solo modificaban documentación pasaron en menos de 1 minuto sin compilar microservicios innecesariamente, ahorrando recursos del ejecutor de Jenkins.
- **Idempotencia de los scripts de infraestructura:** La suite en Bash (`infra/azure/provision.sh` y `bootstrap-all.sh`) demostró ser verdaderamente idempotente: la segunda ejecución verificó los recursos existentes y finalizó con código 0 sin modificar la topología activa ni duplicar reglas en los NSG.
- `<!-- completar en equipo: otros aciertos individuales observados por los integrantes -->`

### 3.2 Qué no funcionó bien (Dificultades y Hechos Reales)
- **PRs fusionados con checks en rojo antes de formalizar los Quality Gates:** En las fases iniciales del sprint, antes de que el ruleset de GitHub estuviera plenamente activo y el pipeline de Jenkins bloqueara con SonarQube, se fusionaron PRs con pruebas en rojo (por ejemplo, el PR #3 requirió corrección posterior en PR #12 debido a pruebas unitarias que rompían el build, y la Shared Library referenciaba una rama eliminada que hizo fallar builds subsiguientes). Esto evidenció la necesidad de compuertas automáticas infranqueables.
- **Bloqueo severo por cuotas y disponibilidad de Azure for Students:** La suscripción académica impuso un límite estricto de **6 vCPU por región**. Además, al intentar crear máquinas virtuales de la serie D/E o B4ms, la API de Azure rechazó las solicitudes con el error `SkuNotAvailable (Capacity Restrictions)` por escasez de cómputo en `westus2`. Esto detuvo el avance temporalmente y forzó una reestructuración de la topología en dos regiones (`westus2` y `chilecentral`) usando la familia `Bsv2`.
- **Diferencias de contexto en clones de Jenkins:** Durante las pruebas en Jenkins, los clones de git en modo *detached HEAD* o *sparse checkout* no incluían las referencias remotas completas (`refs/remotes/origin/*`), provocando que la detección de cambios (`git diff origin/main...HEAD`) fallara silenciosamente y compilara siempre todos los proyectos.
- `<!-- completar en equipo: dificultades observadas en la coordinación y pruebas locales -->`

### 3.3 Qué debemos mejorar para el siguiente ciclo (Acciones de Mejora)
1. **Verificación local estricta antes de abrir PRs:** Ejecutar obligatoriamente `./gradlew test jacocoTestReport` en local para evitar subir ramas con quiebres en pruebas unitarias.
2. **Ambientes efímeros para agentes de Jenkins:** En la Parte 2, evolucionar el nodo de Jenkins hacia agentes de build basados en contenedores efímeros (Docker-in-Docker o agentes SSH dedicados), desacoplando las herramientas del socket Docker del host.
3. **Monitoreo preventivo del consumo en la nube:** Programar paradas automatizadas de las máquinas virtuales en Azure (`az vm deallocate`) fuera del horario de trabajo para preservar los créditos restantes de la suscripción.
4. `<!-- completar en equipo: compromisos de mejora acordados para la Parte 2 -->`

---

## 4. Métricas del Sprint 1

Los siguientes indicadores resumen el rendimiento del ciclo de desarrollo y entrega continua durante el Sprint 1:

| Métrica | Definición | Valor del Sprint 1 | Observaciones |
|---|---|:---:|---|
| **Número total de PRs gestionados** | Total de Pull Requests integrados a `main` durante el ciclo. | `<!-- completar -->` (20+ PRs) | Historial lineal con squash merge; trazabilidad con issues `CG-<n>`. |
| **Tiempo promedio del pipeline CI (PR)** | Duración de la validación CI en ramas de PR (compilación selectiva, unit tests y Sonar). | `<!-- completar -->` (~4-7 min) | Reducido significativamente en PRs de solo documentación (< 1 min). |
| **Tiempo de pipeline completo en `main`** | Desde el merge hasta el despliegue automático en `dev` y `stage`. | `<!-- completar -->` (~12-18 min) | Incluye compilación, JaCoCo, SonarQube, publicación a Nexus y pruebas E2E con Playwright. |
| **Lead Time de cambio** | Tiempo transcurrido desde el primer commit en la rama de trabajo hasta el pase exitoso a producción. | `<!-- completar -->` | `<!-- completar en equipo: estimación basada en logs de Jenkins y GitHub -->` |
| **Cobertura de pruebas unitarias en servicios núcleo** | Porcentaje de líneas de código cubiertas por JaCoCo en `auth`, `identity`, `promotion`, `form` y `gateway`. | $\ge 60.0\%$ | Enforzado por la compuerta `jacocoTestCoverageVerification` en el build Gradle. |
| **Idempotencia de infraestructura** | Tasa de éxito en re-ejecución de scripts Bash de infraestructura (`provision.sh`, `bootstrap-all.sh`). | 100 % (Exit code 0) | Cero recursos duplicados; NSGs e IPs convergieron sin fallos. |
