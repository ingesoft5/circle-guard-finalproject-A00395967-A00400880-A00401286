# Registro de uso de IA generativa (IAG)

**Nivel de uso declarado:** Nivel 3, colaboración con IAG (política del curso).
Cada integrante registra aquí qué le entregó a la IA, qué le pidió, qué obtuvo y qué revisó o cambió.

---

## Simón Reyes: pipeline CI/CD (Jenkins)

**Herramienta:** Claude Code (modelo Claude Opus 5.5), sesión del 6 de noviembre de 2026.

### Contenido propio entregado a la IA

- El enunciado del proyecto (`Proyecto_Final_CircleGuard.pdf`).
- El repositorio del equipo (rama `main` y ramas abiertas).
- La guía de rol "Pipeline CI/CD con Jenkins" con mis work items de Jira (CG-43, CG-44, CG-48 a CG-61, CG-64).
- La convención del equipo: Conventional Commits con descripciones en inglés.

### Prompts empleados (resumen)

1. Leer el enunciado y el repositorio, e indicar qué partes tiene la Parte 1 y cuáles me corresponden.
2. Explicar de dónde salían las responsabilidades de los demás integrantes y los nombres de las ramas.
3. Ordenar mis tareas: cuáles se pueden hacer hoy sin depender de nadie y cuáles dependen de los servidores, Dockerfiles o compose.
4. Separar qué puede escribir la IA y qué debo configurar yo (Jenkins, GitHub, SonarQube, Discord).
5. Escribir todo el código que no depende de mí, solo con commits locales (sin push), para revisarlo juntos.

### Qué produjo la IA

| Entregable | Archivos |
|---|---|
| Shared Library y Jenkinsfile (CG-50 a CG-60) | `Jenkinsfile`, `jenkins/vars/*.groovy` |
| Job de rollback (CG-61) | `jenkins/Jenkinsfile.rollback` |
| Imagen de Jenkins con plugins (CG-48) | `jenkins/local/`, `jenkins/README.md` |
| JaCoCo, Sonar y tarea `integrationTest` en Gradle | `build.gradle.kts` |
| Prueba de integración QR auth → gateway (CG-43) | `services/circleguard-gateway-service/src/test/.../QrAuthGatewayIntegrationTest.java` |
| Base de Playwright (CG-44) | `tests/e2e-playwright/` |
| Diagrama del pipeline (CG-64) | `docs/arquitectura/pipeline.md` |

### Revisión crítica y cambios respecto a la guía

- **El wrapper de Gradle no estaba en el repositorio** (`*.jar` en `.gitignore`), así que `./gradlew` fallaba en cualquier clon limpio. Se regeneró y se agregó una excepción.
- **`./gradlew :services:<svc>:sonar` no existe**: el plugin de SonarQube solo crea la tarea en el proyecto raíz. Se verificó localmente y se cambió por `./gradlew sonar -PsonarService=<svc>`, que omite los demás módulos.
- **`agent none` en la etapa de aprobación no basta** si el pipeline tiene `agent any` global: el ejecutor seguiría ocupado. Se dejó el pipeline sin agente global y cada etapa pide el suyo.
- **La prueba del QR depende de auth sin sus dependencias transitivas**: si se traían, Spring Security entraba al classpath de gateway y rompía su `@WebMvcTest`.
- **Las IPs no se escriben en el repositorio**: se leen de variables globales de Jenkins (`CG_*`).
- La prueba de integración se ejecutó localmente con Docker (8 casos en verde contra Redis real).

### Pendiente de verificar en el servidor

El quality gate, la publicación en Nexus, los despliegues, los E2E y las notificaciones se validan cuando estén el servidor de herramientas (Darío) y los Dockerfiles y compose (Luis).

### Sesión 2 (7 de noviembre de 2026): puesta en marcha y correcciones

**Contenido propio entregado a la IA:** las respuestas de Luis y Darío a mis preguntas (Dockerfiles, compose, puertos, `cliff.toml`, Nexus por HTTP, usuario `deploy`), los logs de mi Jenkins local y las capturas de pantalla de Jenkins, GitHub y Discord.

**Prompts empleados (resumen):**
1. Apoyo en la configuración de Jenkins (local y servidor), credenciales y notificaciones.
2. Apoyo para documentar el avance y coordinar con el equipo (mensajes, revisión de PRs).
3. Análisis de los errores del pipeline y sus correcciones.

**Qué produjo la IA y qué revisé:**

| Tema | Resultado |
|---|---|
| Jenkins local | Imagen construida, credenciales `github-pat`, `discord-webhook` y `deploy-ssh`, librería, job multibranch con estrategia *merge* y job de rollback |
| Notificaciones (CG-60) | Mensaje real de fallo en `#circleguard-ci` |
| Coordinación con el equipo | Mensajes con los pendientes para Darío y Luis, y revisión de sus PRs antes de aprobarlos |

**Errores que encontramos al probar y cómo se corrigieron (revisión crítica):**

- **La librería apuntaba a una rama borrada.** Configuré `circleguard-lib` con la rama del PR #3; al fusionarlo se borró la rama y todos los builds fallaron antes de empezar. Se cambió la versión por defecto a `main`.
- **La ejecución selectiva no funcionaba en PRs (PR #14).** El clon de un PR no trae `origin/main`, así que siempre se construía todo. El pipeline ahora descarga la rama destino antes de comparar. Verificado: el PR #18 (solo documentación) no construyó ningún servicio y quedó en verde.
- **La cobertura solo se reportaba (PR #19).** Darío dejó la regla de 60 % en `check`, pero el pipeline corría `test`. Ahora corre `jacocoTestCoverageVerification` en los servicios núcleo. La IA introdujo al principio una variable duplicada que habría fallado en Jenkins; se detectó con un análisis semántico de Groovy antes del commit.
- **Los PRs se quedaban esperando un ejecutor.** Sin `beforeAgent true`, la etapa *Deploy PROD* reservaba un agente solo para saltarse. Se agregó `beforeAgent`.
- **`REGISTRY` no llegaba al compose.** El compose de Luis usa `${REGISTRY}`; el deploy ahora lo exporta desde `CG_REGISTRY`.

**Lección:** varias suposiciones de la IA (y de la guía) solo se pudieron confirmar ejecutando el pipeline. Probar en un Jenkins local antes de tener los servidores permitió encontrar estos errores a tiempo.

### Sesión 3 (8 de noviembre de 2026): servidor, releases y rollback

**Contenido propio entregado a la IA:** las respuestas de Luis y Darío (IPs de los servidores, variables de Jenkins, orden de merge, ajustes de SonarQube), los logs de Jenkins del servidor y las capturas de pantalla de Jenkins, GitHub y Discord.

**Prompts empleados (resumen):**
1. Apoyo en la configuración de Jenkins en el servidor (credenciales, variables, SonarQube, Shared Library, jobs multibranch y de rollback).
2. Análisis de los fallos del pipeline (quality gate, E2E, versionado, rollback) y sus correcciones.
3. Apoyo para organizar las evidencias y el documento para el informe.

**Qué produjo la IA y qué revisé:**

| Tema | Resultado |
|---|---|
| Jenkins del servidor | Pasos de configuración; las credenciales las ingresé yo directamente en Jenkins, sin compartirlas |
| Quality gate (CG-53) | PR de demostración #26 bloqueado por SonarQube |
| E2E (CG-57) | Corrección de selectores (#33), verificada contra dev antes de subirla: 9/9 |
| Release v1.0.1 | Corrección del gateway (#34) con pruebas; recorrió dev → E2E → stage → aprobación → prod |
| Rollback (CG-61) | Corrección del job (#35); rechazo y rollback de prod demostrados |

**Errores que encontramos y cómo se corrigieron (revisión crítica):**

- **El quality gate siempre daba OK.** La configuración de Sonar que se agregó en el PR #3 omitía el proyecto intermedio `:services`, y con él todos los servicios: Sonar analizaba 0 líneas de Java. Darío lo detectó y lo corrigió (PR #29). Lección: un gate que nunca falla hay que probarlo con código malo a propósito (PR #26).
- **La v1.0.0 no pasó de dev.** Los selectores E2E coincidían con varios elementos o con el fondo oculto. El pipeline bloqueó la promoción a stage, como debía. Se corrigieron los selectores y se verificaron contra dev antes del siguiente release.
- **Relanzar main no generaba versión.** Los commits `test` y `docs` no generan release según semver; hizo falta un `fix` real (el gateway dejaba entrar a usuarios CONFIRMED, PR #34).
- **El job de rollback no arrancaba.** Un `node {}` directo dentro de `post` en un pipeline declarativo se interpreta como directiva de agente. No se había probado en el servidor; se corrigió con una función auxiliar (PR #35).
- **Jenkins borraba los builds de los PRs cerrados.** Se configuró para conservarlos 7 días y no perder evidencias.

**Lección:** el código del pipeline (sugerido o propio) tiene que probarse en el ambiente real; varios errores solo aparecieron al ejecutar en el servidor. Probar cada corrección antes de subirla (por ejemplo, los E2E contra dev) evitó gastar releases.
