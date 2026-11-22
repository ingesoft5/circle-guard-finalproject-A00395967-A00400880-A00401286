# Mapa de impacto del pipeline

Para cada etapa del [`Jenkinsfile`](../../Jenkinsfile) y de la Shared Library [`jenkins/vars`](../../jenkins/vars): qué produce, quién lo usa después, qué garantía da y qué pasaría si se quita o se cambia. Sirve para responder con seguridad a "¿qué pasa si…?" y para decidir **dónde** tocar ante un cambio.

> **Alcance y evidencia.** Todo lo de este documento sale del código de esta rama (`Jenkinsfile`, `jenkins/vars/*.groovy`, `jenkins/Jenkinsfile.rollback`, `build.gradle.kts`). En esta rama el `Jenkinsfile` versiona con `vX.Y.Z` y despliega por SSH + Docker Compose; **no contiene** la convención `-SNAPSHOT.<build>` ni el despliegue a Kubernetes (`grep -i snapshot Jenkinsfile jenkins` no devuelve nada). Si esas partes ya están en `main` al momento de la sustentación, este mapa debe releerse contra ese código: *pendiente de evidencia* para ellas.

## Cómo se reparte el trabajo

- **`Jenkinsfile`** solo describe el **flujo**: orden de las etapas, cuándo corren (`when`), agentes, opciones y notificaciones (`Jenkinsfile:8-9`).
- **Shared Library** (`jenkins/vars`, librería `circleguard-lib`) contiene el **cómo** de cada etapa. El Jenkinsfile principal y el de rollback la comparten.
- **`cgConfig`** es la configuración central: lista de servicios, nombre de imágenes, validación semver y lectura de variables globales de Jenkins (`CG_*`).

## Etapas de CI (en todo PR y en main)

| Etapa (Jenkinsfile) | Llama a | Qué produce | Quién lo usa después | Garantía que da | Si se quita o cambia |
|---|---|---|---|---|---|
| Checkout (`:36`) | `checkout scm` | Código y `env.GIT_COMMIT`, `GIT_PREVIOUS_SUCCESSFUL_COMMIT` | `Detectar cambios` (punto de comparación en main) y todas las etapas | Se construye exactamente el commit disparado | Sin él no hay código (`skipDefaultCheckout` está activo). Si no se guarda el commit previo, `cgChangedServices` cae a "construir todo" |
| Detectar cambios (`:46`) | `cgChangedServices` | `env.SERVICES` (backend) y `env.WEB_CHANGED` | Los `when` de Compilar, Unitarias, Web, Sonar e Integración | Solo se prueba lo afectado; un cambio en archivos globales (`build.gradle.kts`, `settings.gradle.kts`, `gradle/**`, `Jenkinsfile`, `jenkins/**`) o el parámetro `BUILD_ALL` construye todo (`cgChangedServices.groovy:80-85`) | Si se quita, o `SERVICES` queda vacío, **todas las etapas con `when { env.SERVICES }` se saltan en silencio** y un PR pasa en verde sin probar nada. Si se agrega un servicio nuevo hay que añadirlo a `cgConfig.backendServices()` o nunca se detectará |
| Compilar (`:58`) | `cgBuild` | Clases main y test de cada servicio | Unitarias, Sonar | El código compila (`./gradlew :services:<s>:classes :testClasses`) | Quitarla no rompe nada inmediato (Gradle compila en las pruebas) pero se pierde el fallo rápido y separado |
| Pruebas unitarias + JaCoCo (`:63`) | `cgUnitTest` | Resultados JUnit y reporte JaCoCo XML; panel de cobertura | Sonar (lee la cobertura), personas | Pruebas verdes y, en los 5 servicios núcleo (`cgConfig.coreServices()`), cobertura de líneas ≥ 60 % (`jacocoTestCoverageVerification`, `build.gradle.kts:121`). Con `--continue` se ven todos los fallos en un build | Quitarla elimina la garantía de calidad básica. Cambiar el 60 % se hace en `build.gradle.kts`, no en la librería (el umbral de 60.0 del panel está en `cgUnitTest.groovy:38`, con criticidad `UNSTABLE`) |
| Cliente web (build) (`:68`) | `cgWebCheck` | `mobile/dist` (descartable) | Nadie (solo valida) | Un PR que rompe el build web se detecta antes de empaquetar (`npm ci` + `expo export`) | Sin ella el error aparece recién en `docker build` de la publicación, ya en main |
| SonarQube + Quality Gate (`:73`) | `cgSonar` (por servicio) | Análisis en SonarQube y estado del gate | La etapa siguiente: si el gate falla, **aborta** (`abortPipeline: true`) | Nada se empaqueta ni publica con un gate en rojo | Quitar `abortPipeline` convierte el gate en decorativo. Sin el webhook `/sonarqube-webhook/` la etapa espera 10 min y falla (`cgSonar.groovy:21`) |
| Pruebas de integración (`:78`) | `cgIntegrationTest` | Resultados JUnit de `integrationTest` | Personas; es la última puerta de CI | Los servicios se integran (Testcontainers; corre **todas** las pruebas de integración, no solo las del servicio cambiado) | Quitarla deja pasar roturas entre servicios. Requiere Docker en el agente |

Esta es la parte que corre en un PR: es el check obligatorio de `main` (`Jenkinsfile:3-4`).

## Etapas de CD (solo en `main` y solo si hay versión nueva)

`isRelease()` (`Jenkinsfile:192`) es `BRANCH_NAME == 'main'` **y** `env.VERSION` no vacía. Es el interruptor de todo lo que sigue.

| Etapa | Llama a | Qué produce | Quién lo usa después | Garantía que da | Si se quita o cambia |
|---|---|---|---|---|---|
| Versión (`:85`) | `cgVersion` | `env.VERSION` (`v1.0.0` si no hay tags; si no, `git cliff --bumped-version`; `''` si solo hay docs/chore/ci/test) | **Todas** las etapas CD y `currentBuild.displayName`, y notificaciones | La versión sale de los Conventional Commits, no de una persona | Si devuelve `''`, el build termina tras CI sin publicar. Cambiar la regla de bump = editar `cliff.toml`, no el código. Los mensajes de commit mal escritos cambian el bump |
| Empaquetar y publicar (`:99`) | `cgDockerPublish` sobre `cgConfig.allComponents()` | 9 imágenes (8 servicios + web) `…/circleguard/<nombre>:vX.Y.Z` en Nexus, con etiquetas OCI de versión y commit | Deploy DEV, STAGE y PROD, y el rollback (redespliega imágenes ya publicadas) | Nunca `latest` (`cgConfig.requireSemver` lo impide); una versión = un release completo del sistema | Publicar solo las imágenes cambiadas rompería el compose, que usa la misma `VERSION` para todos. Si falla el `docker push`, no se llega a crear el tag |
| Tag + release notes (`:109`) | `cgRelease` | Tag anotado `vX.Y.Z` en GitHub, `RELEASE_NOTES.md` (git-cliff), GitHub Release | `cgVersion` del siguiente build (usa el último tag), `Jenkinsfile.rollback` (`assertTagExists`) | Se ejecuta **después** de publicar: no existe tag de una versión sin imágenes | Moverla antes de publicar o después de producción cambia el significado del tag. Observación: hoy el tag se crea **antes** de la aprobación de producción, así que una versión puede quedar etiquetada sin llegar a prod |
| Deploy DEV (`:114`) | `cgDeploy(env:'dev')` | Ambiente dev en `VERSION`; `.deployed-version` en el servidor | E2E | `docker compose up --wait` con timeout de 600 s: todos los contenedores `healthy` o el build falla | Sin él el E2E probaría un ambiente viejo |
| E2E sobre DEV (`:119`) | `cgE2E` | Reportes JUnit y HTML de Playwright | Etapa Deploy STAGE | Si falla, **stage no recibe la versión** | Quitarla permite promover una versión sin probar el flujo de usuario. La versión de la imagen Playwright se lee de `package.json` |
| Deploy STAGE (`:124`) | `cgDeploy(env:'stage')` | Ambiente stage en la misma `VERSION` | Aprobación PROD | Misma versión que dev (*build once, deploy many*) | — |
| Aprobación PROD (`:135`) | `input` (sin agente, 2 h) | `env.PROD_APPROVER` | Deploy PROD y notificación | Solo `cgConfig.prodApprovers()` (`luis,simon,dario` por defecto) pueden aprobar; no ocupa ejecutor | Si se rechaza o vence: el build queda *aborted* y se notifica; prod no cambia. Quitarla elimina el control humano exigido por la política de cambios |
| Deploy PROD (`:153`) | `cgDeploy(env:'prod')` | Prod en `VERSION`; `PROD_DEPLOYED=true` | Notificación `released` | Misma imagen aprobada en stage; `beforeAgent true` evita pedir ejecutor en cada PR | Quitar `beforeAgent` hace que cada PR espere un ejecutor solo para saltarse la etapa (corregido en #20) |
| `post` (`:171`) | `notify` → `cgNotify` | Mensaje en Discord | Personas | Avisa fallo, inestable, aborto y release a producción | Una falla al notificar no cambia el resultado del build (solo advertencia, `cgNotify.groovy:41`) |

## Piezas transversales de la librería

| Pieza | Qué hace | Quién la usa | Riesgo si cambia |
|---|---|---|---|
| `cgConfig.requireSemver` | Exige `vMAJOR.MINOR.PATCH` (`^v\d+\.\d+\.\d+$`) | `imageRef`, `cgDockerPublish`, `cgDeploy`, `cgRelease`, rollback | Es la compuerta contra `latest`. **Cualquier etiqueta nueva (p. ej. `-SNAPSHOT.<n>`) será rechazada** hasta ampliar esta expresión |
| `cgConfig.host/registry/…` | Lee `CG_*` de las variables globales de Jenkins; falla si falta una obligatoria | Deploy, publicar, E2E | Cambiar una IP no requiere commit; si falta la variable, el build falla con mensaje claro |
| `cgDeploy.currentVersion` | Lee `.deployed-version` del servidor | `cgDeploy`, rollback (antes/después) | Si no se escribe el archivo, el rollback no puede mostrar "antes" |
| Credenciales (`github-pat`, `nexus-docker`, `deploy-ssh`, `sonar-token`, `discord-webhook`) | Se inyectan con `withCredentials`/`sshagent` | Versión, release, publicar, deploy, Sonar, notificar | Renombrar una credencial en Jenkins rompe la etapa que la usa |

## Job de rollback (`jenkins/Jenkinsfile.rollback`)

| Etapa | Qué produce | Garantía | Si se quita o cambia |
|---|---|---|---|
| Validar | `VERSION`, `PREVIOUS_VERSION` | Semver válido, motivo obligatorio, el tag existe en GitHub (hay imágenes publicadas), no es la misma versión | Sin `assertTagExists` se podría hacer rollback a una versión que nunca se publicó |
| Aprobación (solo `prod`, 30 min) | `APPROVER` | Mismos aprobadores que el despliegue | Sin ella cualquiera con acceso al job cambia producción |
| Rollback | Ambiente en la versión elegida | `cgDeploy` y luego verifica que el ambiente reporte esa versión | **No revierte la base de datos** (Flyway solo avanza, `Jenkinsfile.rollback:8-10`): las migraciones deben ser compatibles hacia atrás |

## Si el profesor pide un cambio en vivo

Regla práctica: si cambia **el orden, las condiciones o los parámetros del flujo** → `Jenkinsfile`. Si cambia **cómo se hace** una etapa o valores compartidos → Shared Library. Recordar que tocar `Jenkinsfile` o `jenkins/**` marca un cambio global y **construye todos los servicios** (`cgChangedServices.groovy:80-85`), por lo que el build tarda más.

| Cambio pedido | Dónde | Riesgo |
|---|---|---|
| Agregar o quitar una etapa, o cambiar su orden | `Jenkinsfile` | Una etapa después de `Empaquetar y publicar` que falle deja imágenes (y quizá tag) publicados sin desplegar. Respetar `when { isRelease() }` |
| Cambiar la cobertura mínima (p. ej. 60 → 70 %) | `build.gradle.kts:121` (regla) y `cgUnitTest.groovy:38` (panel) | Los servicios núcleo con menos cobertura dejan el build en rojo; es global y reconstruye todo |
| Hacer el quality gate no bloqueante | `cgSonar.groovy:22` (`abortPipeline`) | Se pierde la garantía de calidad antes de publicar; decirlo explícitamente |
| Agregar un servicio al pipeline | `cgConfig.backendServices()` (y `coreServices()` si exige cobertura) | Necesita Dockerfile en `services/<s>/Dockerfile` y proyecto Sonar; si no, falla en publicar |
| Cambiar quién aprueba prod | Variable global `CG_PROD_APPROVERS` (sin commit) o el default en `cgConfig.prodApprovers()` | Quien no esté en la lista no podrá aprobar; el build espera hasta 2 h |
| Cambiar el tiempo de espera de aprobación | `Jenkinsfile:139` | Menos tiempo = builds abortados si nadie responde |
| Cambiar la regla de versión (qué sube MAJOR/MINOR/PATCH) | `cliff.toml` | Afecta `cgVersion` y las release notes a la vez |
| Cambiar el formato de etiqueta de versión | `cgConfig.requireSemver` **y** quienes la usan (publicar, deploy, release, rollback) | Alto: una sola expresión valida cinco consumidores; probar con rollback también |
| Cambiar el mensaje de Discord | `cgNotify.groovy` | Bajo; no afecta el resultado del build |
| Desplegar a otro host/registro | Variables globales `CG_*` en Jenkins | Nulo en el repo; si falta una, falla con mensaje claro |
| Saltar E2E para una demo | `Jenkinsfile:119` (`when`) | Stage recibe una versión no probada end-to-end |
| Re-ejecutar forzando todo | Parámetro `BUILD_ALL` al lanzar | Ninguno; solo más lento |

**Consejo para el vivo:** hacerlo en una rama, abrir PR y mirar el build de esa rama: en un PR solo corre CI (las etapas CD exigen `main`), así que un error en CD no llega a producción hasta fusionar.
