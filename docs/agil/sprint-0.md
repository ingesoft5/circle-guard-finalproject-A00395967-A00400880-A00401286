# Sprint 0: Fundamentación Ágil, Repositorio y Acuerdos de Equipo

**Periodo:** 3 al 5 de noviembre de 2026  
**Marco de trabajo:** Scrum adaptado para entregas de alta intensidad técnica  
**Proyecto:** CircleGuard (Monorepo Spring Boot 3.2 / Java 21 y React Native Expo)  
**Organización en GitHub:** `ingesoft5`  
**Proyecto Jira:** `CG` (66 issues organizados en 8 épicas)

---

## 1. Objetivo del Sprint 0

El objetivo primordial del **Sprint 0** fue establecer los cimientos operativos, organizacionales y técnicos del proyecto CircleGuard antes de iniciar el ciclo intensivo de desarrollo e integración. Esto comprendió:
1. Crear y estructurar el repositorio monorepo en GitHub bajo la organización `ingesoft5`.
2. Modelar y poblar el backlog en Jira con 66 issues categorizados en 8 épicas alineadas con los requisitos de la Parte 1.
3. Definir y formalizar la estrategia de versionado y branching basada en **GitHub Flow**.
4. Blindar la rama `main` mediante reglas de protección estrictas (*Ruleset* de GitHub).
5. Acordar los estándares de calidad del equipo: **Definition of Ready (DoR)** y **Definition of Done (DoD)**.
6. Habilitar los canales de comunicación y coordinación rápida para el equipo.

---

## 2. Justificación del Marco: ¿Por qué Scrum Adaptado?

El equipo adoptó una versión adaptada de **Scrum** diseñada para proyectos universitarios de alta exigencia de ingeniería de software bajo las siguientes justificaciones:

- **Iteraciones cortas y enfocadas:** Se definieron ciclos breves (Sprint 0 de 3 días para fundamentación; Sprint 1 de 3 días para implementación del pipeline, infraestructura y pruebas) que permitieron retroalimentación inmediata y mitigación temprana de riesgos.
- **Fecha fija e improrrogable (*Timeboxing estricto*):** Con una fecha límite de entrega fijada para el **domingo 8 de noviembre de 2026 a las 23:59**, el equipo no podía operar en cascada. El timeboxing forzó la priorización de los artefactos críticos (pipeline CI/CD, infraestructura multi-ambiente, pruebas automáticas).
- **Especialización de roles complementarios:** La carga de trabajo se distribuyó de acuerdo con las fortalezas técnicas de cada integrante, manteniendo autonomía en la ejecución y responsabilidad compartida en las revisiones.
- **Ceremonias ligeras y comunicación asíncrona:** En lugar de reuniones extensas, se implementaron sincronizaciones diarias cortas (*Daily standups*) de 10 minutos vía WhatsApp y Discord, complementadas con revisiones y retrospectivas presenciales/virtuales al cierre de cada sprint.

### Herramientas de soporte
- **Jira Software:** Gestión del backlog, tablero Kanban/Scrum, seguimiento de estados e impedimentos (`CG-1` a `CG-66`).
- **GitHub (`ingesoft5`):** Alojamiento del código fuente, pull requests con revisión por pares, disparador de webhooks y protección de ramas.
- **Discord (`#circleguard-ci`):** Canal centralizado de notificaciones automáticas del pipeline de Jenkins (fallos de compilación, calidad, despliegues y aprobaciones).
- **WhatsApp:** Coordinación ágil inmediata, alertas de bloqueos y acuerdos rápidos de equipo.

---

## 3. Definition of Ready (DoR) y Definition of Done (DoD)

Para evitar la entrada de requisitos ambiguos al sprint y garantizar que los incrementos alcancen calidad de producción, el equipo estableció los siguientes criterios:

### Definition of Ready (DoR)
Una historia o tarea técnica se considera **Ready** (Lista para iniciar) cuando:
1. **Redacción clara:** La historia está descrita con claridad, identificando el rol, la necesidad y el valor ("Como... Quiero... Para..."), o como tarea técnica específica con alcance acotado.
2. **Criterios de aceptación objetivos:** Cuenta con condiciones de satisfacción explícitas, medibles y verificables (enfoque *Given-When-Then* o lista de comprobación técnica).
3. **Responsable único asignado:** Posee un único integrante asignado como líder técnico de su ejecución.
4. **Dependencias identificadas:** Se conocen de antemano las dependencias de código, librerías o infraestructura (ej. "requiere que el contenedor de Nexus esté activo").
5. **Estimación y dimensionamiento:** Está dimensionada para completarse en un plazo menor a 24 horas de esfuerzo dentro del sprint.

### Definition of Done (DoD)
Un issue o Pull Request se considera **Done** (Terminado) únicamente cuando:
1. **Estándares de codificación:** El código sigue los estándares de Java 21 / Spring Boot 3.2 o TypeScript / Expo, manteniendo comentarios y documentación en español.
2. **Pruebas automatizadas y cobertura:** Las pruebas unitarias y de integración pasan sin errores. Los 5 microservicios núcleo (`auth`, `identity`, `promotion`, `form`, `gateway`) cumplen la meta de al menos **60 % de cobertura de líneas** verificada por JaCoCo.
3. **Cero secretos en código:** No existen credenciales, tokens, contraseñas ni llaves privadas en el repositorio. Toda configuración sensible se parametriza mediante variables de entorno y archivos `.env.example`.
4. **Imágenes Docker inmutables (sin `latest`):** Ninguna imagen de Dockerfile ni compose utiliza la etiqueta `latest`. Se emplean versiones semánticas fijas o referencias `${VERSION}`.
5. **Nomenclatura de rama:** La rama se creó desde `main` actualizado con el patrón `<tipo>/CG-<n>-short-description`.
6. **PR con plantilla y título Conventional Commits:** El PR se abre usando `.github/pull_request_template.md`, con título en inglés imperativo (`feat(...)`, `fix(...)`, `docs(...)`) para alimentar `git-cliff` en el merge.
7. **Revisión por pares aprobada:** Cuenta con al menos una aprobación formal (1 approval) de un compañero tras inspeccionar el diff.
8. **Pipeline CI en verde:** El pipeline de Jenkins ejecuta con éxito la compilación, pruebas unitarias, JaCoCo y supera el Quality Gate bloqueante de SonarQube.
9. **Squash and Merge:** La integración a `main` se realiza exclusivamente mediante Squash and Merge, preservando un historial lineal y atómico.
10. **Documentación sincronizada:** Se actualizan los diagramas o especificaciones en `docs/` y la bitácora en `IAG_REGISTRO.md`.

---

## 4. Estructura del Backlog (Épicas E1.1 a E1.8)

El backlog del proyecto en Jira fue organizado en 8 épicas principales derivadas de las especificaciones de la entrega Parte 1:

| Épica | Nombre | Alcance y Entregables |
|---|---|---|
| **E1.1** | Gestión ágil y branching | Tablero Jira con 66 historias, acuerdos de trabajo (DoR/DoD), documentación de Sprint 0 y Sprint 1, estrategia de branching GitHub Flow y reglas de protección de rama. |
| **E1.2** | Infraestructura en la nube con Bash | Aprovisionamiento en Azure mediante scripts Bash idempotentes (`infra/azure/provision.sh`, `infra/bootstrap/*.sh`), 4 máquinas virtuales aisladas (tools, dev, stage, prod) y topología de red NSG. |
| **E1.3** | Empaquetamiento inmutable | Dockerfiles multi-stage y no root para los 8 microservicios y el cliente web (`expo export`), imágenes versionadas en Nexus (sin `latest`) y archivos compose con healthchecks. |
| **E1.4** | Pipeline Jenkins (CI) | Jenkinsfile multibranch con webhook de GitHub, Shared Library en Groovy, compilación Gradle selectiva por servicio modificado, JaCoCo y Quality Gate bloqueante de SonarQube. |
| **E1.5** | Entrega continua y promoción | Despliegue automatizado a `dev`, promoción a `stage` tras superar E2E, compuerta manual de aprobación (`input`) para `prod`, versionado SemVer automático (`git-cliff`), notificaciones a Discord y job de rollback. |
| **E1.6** | Pruebas automatizadas | Pruebas unitarias ($\geq 60\%$ de cobertura en servicios núcleo), al menos 3 pruebas de integración con Testcontainers (PostgreSQL, Redis, Neo4j, Kafka) y 3 flujos E2E con Playwright. |
| **E1.7** | Change management y release notes | Plantilla de Solicitud de Cambio (RFC), política de aprobación (estándar, normal, emergencia), notas de release automáticas (`cliff.toml`) y etiquetado SemVer. |
| **E1.8** | Documentación y arquitectura | Mapas de servicios y puertos, diagramas Mermaid de arquitectura de aplicación, infraestructura y pipeline, README operativo y bitácora de uso de IA (IAG). |

---

## 5. Asignación de Roles y Responsabilidades

La división del trabajo en el equipo asignó liderazgos claros por área técnica, garantizando al mismo tiempo que las decisiones de arquitectura se tomaran en consenso:

| Integrante | Rol Principal | Épicas Lideradas | Responsabilidades Clave |
|---|---|---|---|
| **Luis Felipe Cadena** | Scrum Master / Release Engineer | **E1.1, E1.3, E1.7** (y soporte en E1.8) | Gestión ágil del backlog en Jira, definición de la estrategia de branching y reglas en GitHub; empaquetamiento inmutable de los 8 microservicios y cliente web Nginx; política de change management, configuración de `git-cliff` y plantillas de RFC y PR. |
| **Simón Reyes** | CI/CD & Pipeline Engineer | **E1.4, E1.5** (y soporte en E1.8) | Diseño e implementación del Jenkinsfile multibranch y Shared Library en Groovy (`jenkins/vars/`); integración del webhook de GitHub, ejecución selectiva de servicios, etapas de SonarQube, despliegues SSH a ambientes, compuerta interactiva de producción y notificaciones a Discord. |
| **Darío Marquínez** | Cloud Infrastructure & QA Engineer | **E1.2, E1.6** (y soporte en E1.8) | Aprovisionamiento de máquinas virtuales en Azure con Bash idempotente y topología multi-región; configuración de NSGs, Docker, SonarQube y Nexus; autoría y estabilización de pruebas unitarias (cobertura $\geq 60\%$), pruebas de integración con Testcontainers y suite E2E con Playwright. |

---

## 6. Entregables y Resultados del Sprint 0

Al cierre del Sprint 0 (5 de noviembre de 2026), el equipo completó satisfactoriamente los siguientes elementos fundacionales:

1. **Repositorio oficial configurado:** Código base del monorepo alojado en GitHub (`ingesoft5/circle-guard-finalproject-...`), con wrapper de Gradle funcional (`gradlew`) e inicializador de base de datos (`init-db.sql`).
2. **Protección de la rama `main` activada:** Reglas aplicadas en GitHub exigiendo Pull Request para todo cambio, 1 aprobación obligatoria de un par, historial estrictamente lineal (*Require linear history*), bloqueo de force push y status check requerido del pipeline de CI.
3. **Tablero Jira estructurado:** Creación del proyecto `CG` con 66 issues categorizados en las 8 épicas (E1.1 a E1.8), con estimaciones y responsables asignados.
4. **Estrategia de branching documentada:** Creación de `docs/agil/branching.md`, especificando el flujo GitHub Flow, convención de ramas `<tipo>/CG-<n>-short-description`, squash merge y el mapeo de Conventional Commits hacia SemVer.
5. **Plantilla de Pull Request operativa:** Publicación de `.github/pull_request_template.md` con selección de tipo de cambio, clave de Jira, recordatorio de Conventional Commits y checklist de seguridad.
6. **Integraciones de comunicación listas:** Servidor de Discord configurado con canal `#circleguard-ci` y webhook vinculado para alertas tempranas del pipeline.
7. **Acuerdos técnicos consolidados:** Formalización del DoR y DoD como criterio vinculante para aceptar cualquier PR durante el Sprint 1.

---

## 7. Sprint Review del Sprint 0

| Objetivo del Sprint 0 | Resultado | Evidencia |
|---|---|---|
| Conformar el equipo y repartir el trabajo | Cumplido: una persona responsable por épica (sección 5) | Jira, épicas E1.1 a E1.8 |
| Definir el marco ágil y la estrategia de branching | Cumplido: Scrum adaptado y GitHub Flow | Sección 2 y `docs/agil/branching.md` |
| Backlog inicial con historias y criterios de aceptación | Cumplido: 66 ítems en 8 épicas, creados en Jira con el conector de Atlassian | Proyecto `CG` en Jira |
| Cargar el código base | Cumplido: repositorio privado creado desde la plantilla del curso, sin copiar sus ramas | Repositorio en `ingesoft5` |
| Proteger `main` | Cumplido después de mover el repositorio a la organización (ver retrospectiva) | Ruleset "Proteger main" |
| Aprovisionar la nube | **No cumplido en el Sprint 0:** se agotó el crédito de Azure for Students; pasó al Sprint 1 con otra suscripción | `docs/infra/topologia.md` |

Además, cada integrante recibió una guía con sus tareas y lo que debe saber para la sustentación.

---

## 8. Retrospectiva del Sprint 0

### 8.1 Qué funcionó bien
- **Reparto claro desde el inicio:** cada épica tuvo un responsable y cada integrante sabía qué entregaba, lo que permitió trabajar en paralelo desde el Sprint 1.
- **Backlog completo en poco tiempo:** generar las historias con sus criterios de aceptación y cargarlas a Jira con el conector evitó crear 66 ítems a mano.
- **Convenciones acordadas antes de escribir código:** nombres de rama, Conventional Commits y squash merge quedaron definidos antes del primer PR.

### 8.2 Qué no funcionó bien
- **El ruleset de `main` no se aplicaba:** en un repositorio privado de una cuenta personal GitHub no aplica los rulesets. Se resolvió moviendo el repositorio a la organización `ingesoft5`.
- **Jira no aceptaba story points:** el campo no estaba en las pantallas de Historia y Tarea; las historias se crearon sin estimación numérica.
- **Los primeros commits estaban en español** porque no había una regla de idioma.
- **La nube no quedó lista:** el crédito de Azure for Students se agotó y el aprovisionamiento se retrasó al Sprint 1.
- **Historias difíciles de encontrar:** el tablero oculta lo terminado, y un integrante no encontraba sus historias.

### 8.3 Acciones de mejora
1. Commits, ramas y títulos de PR en inglés; documentación en español (aplicado desde el Sprint 1).
2. Asegurar los recursos de nube antes de empezar el sprint que los necesita.
3. Agregar el campo de story points a las pantallas de Jira.
4. Revisar la visibilidad del repositorio después de cualquier cambio de organización: debe quedar privado.

