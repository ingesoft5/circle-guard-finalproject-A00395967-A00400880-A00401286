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

---

## Darío Marquínez: infraestructura y calidad (QA)

**Herramienta:** Claude Code (modelo Claude Opus 5.5), sesiones del 7 y 8 de noviembre de 2026.

### Sesión 1 (7 de noviembre de 2026): pruebas e infraestructura en Azure

**Contenido propio entregado a la IA:**
- El enunciado (`Proyecto_Final_CircleGuard.pdf`) y mi guía de rol con los work items de Jira (CG-30 a CG-42, CG-45 a CG-47, CG-62).
- El repositorio del equipo y las preguntas de Simón sobre servidores, registro y credenciales.
- Acceso de lectura y creación a la suscripción Azure for Students de Luis (sesión de `az` iniciada por nosotros). Las contraseñas nunca pasaron por el chat: la IA solo leía archivos `chmod 600` en los servidores cuando un script las necesitaba.

**Prompts empleados (resumen):**
1. Revisar qué pruebas fallaban en `main` y por qué, y corregirlas sin cambiar el comportamiento de los servicios.
2. Subir la cobertura de los 5 servicios núcleo por encima del 60 % e imponerla en el build.
3. Escribir pruebas de integración entre servicios reales (CG-41, CG-42) y E2E de los flujos principales (CG-45 a CG-47).
4. Proponer y crear la infraestructura en la nube con scripts Bash idempotentes (CG-30 a CG-34), y confirmar conmigo antes de gastar crédito.
5. Revisar y comentar los PRs de Luis y Simón.

**Qué produjo la IA:**

| Entregable | Resultado | PR |
|---|---|---|
| Corrección de pruebas unitarias rotas | identity ×4, promotion ×5, form ×1 | #12 |
| Cobertura (CG-35 a CG-40) | ~200 pruebas; auth 98,1 %, identity 94,1 %, promotion 81,4 %, form 97,7 %, gateway 88,9 %; regla de 60 % en `check` | #15 |
| E2E con Playwright (CG-45 a CG-47) | login, cuestionario de salud y acceso al campus con el QR real | #13 |
| Integración (CG-41, CG-42) | form → Kafka → promotion (4) y auth → identity (3) con Kafka, Neo4j, Redis y PostgreSQL reales | #17 |
| Bugs encontrados por esas pruebas | promotion no publicaba eventos; los usuarios sin contactos nunca llegaban a Redis | #16 |
| Infraestructura (CG-30 a CG-34) | `provision.sh`, `bootstrap-all.sh`, `bootstrap-tools.sh`, `bootstrap-env.sh`, configuración de Nexus y SonarQube por API, topología y costos | #22 |

**Revisión crítica y correcciones:**
- **Testcontainers no arrancaba.** Docker Engine 29 rechaza la API 1.32 que usa Testcontainers 1.19 (respondía 400). Se fijó `api.version=1.44` en la tarea `integrationTest`.
- **Las pruebas de integración encontraron dos bugs reales en promotion**: no había configuración de productor de Kafka (los eventos fallaban al serializarse) y la consulta Cypher descartaba su única fila cuando el usuario no tenía contactos. Se corrigieron en el PR #16, separado de las pruebas.
- **Azure respondía `SkuNotAvailable` aunque había cuota.** En vez de probar tamaños a ciegas, la IA escribió `probe-capacity.sh`, que valida una plantilla ARM **sin crear nada**. Solo la familia Bsv2 tenía capacidad, así que se usó Bsv2 y dos regiones (westus2 y chilecentral). Revisé y aprobé el costo (≈ USD 0,46/h) antes de crear las VMs.
- **Registro por HTTP entre regiones.** La IA lo propuso como trade-off explícito y lo acotamos con NSG (solo las 4 VMs llegan al puerto 8082), usuarios de mínimo privilegio en Nexus y `ALLOW_ONCE`. Las tres cosas se probaron (push permitido, sobrescritura rechazada, pull de solo lectura).
- **SonarQube 10 crea los gates nuevos con condiciones propias** y la creación de la condición de cobertura devolvía 400. El script se cambió para *converger* el gate a exactamente nuestras condiciones.
- **Errores de la IA en los scripts**: usó `declare -A` y arreglos vacíos con `set -u`, que fallan en el bash 3.2 de macOS, y un bucle que zsh no separaba por espacios. Se detectaron al ejecutar y se corrigieron.
- **Idempotencia probada, no supuesta**: cada script se ejecutó dos veces; la segunda termina con código 0 y sin cambios (`docs/infra/evidencia/`).

### Sesión 2 (8 de noviembre de 2026): quality gate y release

**Contenido propio entregado a la IA:** los mensajes del grupo (auditoría de Luis, preguntas de Simón sobre el gate del PR #26) y acceso a SonarQube y Jenkins en cg-tools.

**Prompts empleados (resumen):**
1. Averiguar por qué el gate de SonarQube daba OK en el PR #26, que tenía código malo a propósito.
2. Revisar los puntos que Luis me asignó (verificación silenciosa en `bootstrap-env.sh`, selector E2E).
3. Desbloquear el build de `main` para sacar la v1.0.0.

**Qué produjo la IA:**

| Tema | Resultado | PR |
|---|---|---|
| Sonar analizaba 0 líneas de Java | Omitir `:services` omitía a todos sus hijos; además el gate pasó a evaluar código nuevo (últimos 30 días) | #29 |
| `main` no compilaba tras #24 + #17 | La prueba de integración usaba el constructor viejo de `IdentityClient` | #28 |
| Benchmark NFR-1 inestable en Jenkins | Pasó a su propia tarea `performanceTest` | #31 |
| Verificación silenciosa en `bootstrap-env.sh` | `cmd && ok` no dispara `set -e`; ahora termina en `die` | #30 |

**Revisión crítica y correcciones:**
- **Primero se midió y después se cambió el gate.** Antes de tocar el gate real, la IA analizó los 8 servicios en proyectos de prueba (`probe-*`, borrados después). Así encontramos 2 bloqueantes heredados en promotion (`java:S2229`): un gate sobre todo el código habría frenado `main`. La propuesta de "30 días + condiciones sobre todo el código" se descartó con esos datos.
- **La IA se equivocó al probar el código nuevo.** Las dos primeras simulaciones daban OK para el PR #26 porque los issues ya existían de un análisis anterior y Sonar conserva su fecha de creación. Se repitió con un proyecto limpio en el orden correcto (línea base → PR con fechas nuevas): **ERROR** con cobertura nueva 0 %, 3 bloqueantes y 1 vulnerabilidad. De ahí salió la instrucción para Simón (`git rebase --ignore-date`).
- **El choque entre #24 y #17 apareció al simular el merge de todos los PRs abiertos** y compilar el resultado. Por separado, cada PR estaba bien.
- **Una aserción de tiempo no puede bloquear el pipeline**: el benchmark tardaba 436 ms en local y más de 1 s en la VM compartida de Jenkins.
- **Error de coordinación de la IA**: subió un commit a la rama del #28 cuando Luis ya lo había fusionado. Se restauró la descripción del #28 y el cambio salió en un PR nuevo (#31).

**Lección:** un gate que nunca falla hay que probarlo con código malo a propósito, y una herramienta de calidad hay que verificarla con sus propios datos (qué archivos analizó, cuántas líneas), no solo con el resultado OK. Además, la IA también se equivoca al probar: cada conclusión se confirmó con una prueba limpia antes de cambiar el servidor.
