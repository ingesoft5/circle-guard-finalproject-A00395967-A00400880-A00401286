# Jenkins: pipeline CI/CD de CircleGuard

| Archivo | Qué es |
|---|---|
| [`../Jenkinsfile`](../Jenkinsfile) | Pipeline multibranch: CI en todo PR/rama y CD solo en `main` |
| [`Jenkinsfile.rollback`](Jenkinsfile.rollback) | Job de rollback (redesplegar una versión anterior) |
| [`vars/`](vars) | Shared Library `circleguard-lib`: etapas reutilizables |
| [`local/`](local) | Imagen de Jenkins con Docker CLI, git-cliff, jq y plugins + compose para correrlo local |

## Shared Library (`vars/`)

| Paso | Qué hace | Jira |
|---|---|---|
| `cgConfig` | Lista de servicios, nombres de imagen y lectura de las variables globales | — |
| `cgChangedServices` | Detecta qué servicios cambiaron (`git diff`) | CG-51 |
| `cgBuild` | Compila con Gradle | CG-52 |
| `cgUnitTest` | Pruebas unitarias + JUnit + cobertura JaCoCo | CG-52 |
| `cgWebCheck` | `npm ci` + `expo export` del cliente web | — |
| `cgSonar` | Análisis SonarQube + quality gate bloqueante | CG-53 |
| `cgIntegrationTest` | `./gradlew integrationTest` (Testcontainers) | CG-54 |
| `cgVersion` | Siguiente versión semver con git-cliff | CG-59 |
| `cgDockerPublish` | `docker build` + `push` a Nexus | CG-55 |
| `cgRelease` | Tag `vX.Y.Z` + release notes + GitHub Release | CG-59 |
| `cgDeploy` | SSH + `docker compose up --wait` en un ambiente | CG-56/57 |
| `cgE2E` | Playwright contra un ambiente + reportes | CG-57 |
| `cgNotify` | Mensaje a Discord | CG-60 |

## Configuración de Jenkins (una sola vez)

### 1. Levantar Jenkins (local)

```bash
cd jenkins/local
docker compose up -d --build
docker compose exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword
```

Abrir `http://localhost:8080`, pegar la contraseña, elegir **Select plugins → None** (los plugins ya vienen en la imagen) y crear el usuario administrador. Crear también los usuarios `luis`, `simon` y `dario`: son los que pueden aprobar producción.

### 2. Credenciales (*Manage Jenkins → Credentials → System → Global*)

| ID | Tipo | Para qué |
|---|---|---|
| `github-pat` | Username with password (usuario de GitHub + token) | Clonar, leer tags, empujar tags y crear el GitHub Release |
| `nexus-docker` | Username with password | `docker login` al registro |
| `sonar-token` | Secret text | Token de análisis de SonarQube |
| `deploy-ssh` | SSH Username with private key (usuario `deploy`) | Entrar a dev, stage y prod |
| `discord-webhook` | Secret text | URL del webhook del canal de Discord |

El token de GitHub necesita permiso sobre el repositorio (*Fine-grained*: **Contents: Read and write**, **Pull requests: Read**, **Commit statuses: Read and write**, **Metadata: Read**).

### 3. Variables globales (*Manage Jenkins → System → Global properties → Environment variables*)

| Variable | Ejemplo | Obligatoria |
|---|---|---|
| `CG_REGISTRY` | `10.0.1.20:8082` | Sí (para publicar) |
| `CG_DEV_HOST` | `10.0.1.30` | Sí (para desplegar) |
| `CG_STAGE_HOST` | `10.0.1.31` | Sí |
| `CG_PROD_HOST` | `10.0.1.32` | Sí |
| `CG_DEPLOY_USER` | `deploy` | No (por defecto `deploy`) |
| `CG_DEPLOY_DIR` | `/opt/circleguard` | No (por defecto `/opt/circleguard`) |
| `CG_WEB_PORT` | `80` | No: puerto del cliente web para los E2E |
| `CG_PROD_APPROVERS` | `luis,simon,dario` | No: usuarios de Jenkins que aprueban prod |
| `CG_IMAGE_NAMESPACE` | `circleguard` | No |

Las IPs no están en el repositorio: si cambia un servidor, se actualiza aquí sin hacer commit.

### 4. Shared Library (*Manage Jenkins → System → Global Trusted Pipeline Libraries*)

- Name: `circleguard-lib` · Default version: `main` · *Load implicitly*: no.
- Retrieval method: **Modern SCM → GitHub** · credencial `github-pat` · este repositorio.
- **Library Path: `jenkins/`**

### 5. SonarQube

- *Manage Jenkins → System → SonarQube servers*: nombre **`sonar`**, la URL del servidor y la credencial `sonar-token`.
- En SonarQube: *Administration → Configuration → Webhooks* → `http://<jenkins>:8080/sonarqube-webhook/`. **Sin este webhook `waitForQualityGate` se queda esperando hasta el timeout.**
- Quality gate `CircleGuard` (por defecto para los proyectos): cobertura ≥ 60 % en código nuevo, 0 bugs bloqueantes, 0 vulnerabilidades.

### 6. Job multibranch `circleguard`

*New Item → Multibranch Pipeline*:
- Branch Source **GitHub**, credencial `github-pat`, URL del repositorio.
- Behaviours: *Discover branches* y *Discover pull requests from origin*. **No** agregar *Discover tags*: el pipeline crea tags y, si se descubrieran, cada tag dispararía otro build (bucle).
- Build Configuration: *by Jenkinsfile*, Script Path `Jenkinsfile`.

En GitHub: *Settings → Webhooks →* `http://<jenkins>:8080/github-webhook/`, content type `application/json`, eventos **push** y **pull requests**. Jenkins tiene que ser accesible desde internet.

### 7. Job de rollback `circleguard-rollback`

*New Item → Pipeline* → *Pipeline script from SCM* → Git, este repositorio, rama `*/main`, **Script Path `jenkins/Jenkinsfile.rollback`**. La primera ejecución solo carga los parámetros.

## Cómo se comporta

| Evento | Qué corre |
|---|---|
| PR o rama distinta de `main` | Checkout → detectar cambios → compilar → unitarias + JaCoCo → build web → Sonar + gate → integración |
| Merge a `main` con `feat`/`fix`/`!` | Lo anterior + versión → publicar todas las imágenes → tag + release notes → deploy dev → E2E → deploy stage → **aprobación** → deploy prod |
| Merge a `main` solo con `docs`/`chore`/`ci`/`test` | Solo CI: no hay versión nueva, no se publica ni se despliega |

Solo se construyen y prueban los servicios que cambiaron. Un cambio global (Gradle raíz, `Jenkinsfile`, `jenkins/`) o el parámetro `BUILD_ALL` construye todo. En `main` se publican siempre **todas** las imágenes con la nueva versión, porque el compose de cada ambiente usa una sola `VERSION` para todos los servicios.

## Riesgo aceptado: socket de Docker

Montar `/var/run/docker.sock` en Jenkins equivale a dar root sobre el host a los jobs. Se acepta en la Parte 1 porque solo el equipo tiene acceso a Jenkins. En la Parte 2 se mitiga con agentes efímeros en Kubernetes, Kaniko o Docker rootless, y RBAC en Jenkins.
