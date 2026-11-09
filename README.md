# CircleGuard: pipeline CI/CD (Ingeniería de Software V)

CircleGuard es un sistema universitario de rastreo de contactos que identifica
grupos de contacto (*Circles*) y aplica cercos sanitarios sin exponer la
identidad de los estudiantes. Son **8 microservicios Spring Boot** (Java 21), un
**cliente web** (Expo, servido por nginx) y la *middleware*: PostgreSQL, Neo4j,
Kafka, Redis y OpenLDAP.

Este repositorio contiene la aplicación y todo lo necesario para construirla,
probarla y desplegarla de forma automática: infraestructura en Azure con Bash,
pipeline en Jenkins, imágenes inmutables en Nexus y tres ambientes (dev, stage y
prod).

| Recurso | Dirección |
|---|---|
| Jenkins | http://20.230.211.66:8080 |
| SonarQube | http://20.230.211.66:9000 (solo IPs del equipo) |
| Nexus (UI) | http://20.230.211.66:8081 (solo IPs del equipo) |
| Registro Docker | `20.230.211.66:8082` (solo las VMs del proyecto) |
| dev | http://20.114.42.226 |
| stage | http://57.156.56.122 |
| prod | http://57.156.59.208 |
| Notificaciones | Discord, canal `#circleguard-ci` |

---

## 1. Arquitectura

| Documento | Contenido |
|---|---|
| [docs/arquitectura/arquitectura.md](docs/arquitectura/arquitectura.md) | Diagrama de la aplicación y de la infraestructura |
| [docs/arquitectura/servicios.md](docs/arquitectura/servicios.md) | Los 8 servicios: puertos, dependencias y responsabilidades |
| [docs/arquitectura/pipeline.md](docs/arquitectura/pipeline.md) | Diagrama y etapas del pipeline |
| [docs/infra/topologia.md](docs/infra/topologia.md) | Servidores, tamaños, red (NSG), costos y por qué |

```
GitHub ──webhook──► cg-tools (westus2): Jenkins · SonarQube · Nexus (registro :8082)
                         │ SSH + docker compose            ▲ docker pull
                         ▼                                 │
          cg-dev (westus2)   cg-stage (chilecentral)   cg-prod (chilecentral)
```

## 2. Requisitos

En la máquina de quien administra:

| Herramienta | Para qué |
|---|---|
| Azure CLI (`az`) con sesión iniciada (`az login`) | crear la infraestructura |
| Llave SSH (`~/.ssh/id_ed25519.pub`) | acceso de administración a las VMs |
| Bash, `git`, `curl`, `jq` | scripts de `infra/` (funcionan con el Bash 3.2 de macOS) |
| Java 21 y Docker | compilar y correr las pruebas en local |
| Node.js 20 | pruebas E2E y cliente web en local |

Los servidores no necesitan nada instalado: los scripts instalan Docker y lo demás.

## 3. Levantar la infraestructura desde cero

Son **dos comandos**, desde la raíz del repositorio. Ambos son **idempotentes**:
ejecutarlos de nuevo no cambia nada y terminan con código 0 (evidencia en
[docs/infra/evidencia/](docs/infra/evidencia/)).

```bash
./infra/azure/provision.sh       # 1. VMs, red, IPs estáticas y NSG en Azure (~10 min)
./infra/azure/bootstrap-all.sh   # 2. configura los 4 servidores (~15 min la primera vez)
```

| Script | Dónde corre | Qué hace |
|---|---|---|
| [`infra/azure/provision.sh`](infra/azure/provision.sh) | máquina del administrador | Grupo de recursos `circleguard-rg`, una VNet por región, IPs públicas estáticas, un NSG de mínimo privilegio por VM y las 4 VMs (con tamaños de respaldo si Azure no tiene capacidad). `--status` muestra las IPs. |
| [`infra/azure/bootstrap-all.sh`](infra/azure/bootstrap-all.sh) | máquina del administrador | Copia la versión *commiteada* del repo a cada VM (`git archive` por SSH, sin credenciales de GitHub en los servidores) y ejecuta los dos scripts siguientes: primero tools y después los tres ambientes en paralelo. Acepta ambientes como argumento: `./infra/azure/bootstrap-all.sh dev prod`. |
| [`infra/bootstrap/bootstrap-tools.sh`](infra/bootstrap/bootstrap-tools.sh) | cg-tools | Instala Docker, genera los secretos de la máquina, levanta Jenkins, SonarQube (con PostgreSQL) y Nexus, y los configura por API: repositorio Docker `circleguard` (HTTP 8082, `ALLOW_ONCE`), usuarios `jenkins-ci` (push) y `deploy-pull` (solo lectura), quality gate `CircleGuard`, webhook hacia Jenkins y token de análisis. |
| [`infra/bootstrap/bootstrap-env.sh <dev\|stage\|prod>`](infra/bootstrap/bootstrap-env.sh) | cg-dev, cg-stage, cg-prod | Instala Docker, crea el usuario `deploy` con la llave pública de Jenkins, prepara `/opt/circleguard/<env>` con el compose del ambiente, genera su `.env` con secretos propios, hace `docker login` de solo lectura, agrega 4 GB de swap y activa `ufw`. |

Variables opcionales de `provision.sh`: `ADMIN_CIDR` (IP del equipo; por
defecto, la IP pública actual), `SSH_PUBKEY`, `RG`.

Los dos scripts de `infra/bootstrap/` también se pueden ejecutar directamente en
cualquier Ubuntu 22.04 (otra nube o una VM local):

```bash
sudo REGISTRY_HOST=<ip-tools> PUBLIC_HOST=<ip-tools> ./infra/bootstrap/bootstrap-tools.sh
sudo REGISTRY=<ip-tools>:8082 NEXUS_PULL_PASSWORD=<...> ./infra/bootstrap/bootstrap-env.sh dev
```

## 4. Configuración de Jenkins

Los pasos están en [jenkins/README.md](jenkins/README.md):

1. Credenciales: `github-pat`, `nexus-docker`, `sonar-token`, `deploy-ssh` y `discord-webhook`.
2. Variables globales: `CG_REGISTRY`, `CG_DEV_HOST`, `CG_STAGE_HOST`, `CG_PROD_HOST`, `CG_WEB_PORT`, `CG_DEPLOY_USER`, `CG_DEPLOY_DIR`.
3. Shared Library `circleguard-lib` (carpeta `jenkins/` de este repo, rama `main`).
4. Servidor SonarQube `sonar` con URL `http://sonarqube:9000` (red interna de Docker).
5. Job multibranch `circleguard` (webhook de GitHub) y job `circleguard-rollback`.
6. Usuarios aprobadores de producción: `luis`, `simon` y `dario`.

Los valores de las credenciales se leen en cg-tools, en `/opt/cg-tools/.env` y
`/opt/cg-tools/sonar-jenkins.token` (ambos `chmod 600`).

## 5. Pipeline

Detalle en [docs/arquitectura/pipeline.md](docs/arquitectura/pipeline.md).

| Se ejecuta en | Etapas |
|---|---|
| Cada PR y rama | checkout → detección de servicios cambiados → compilación → pruebas unitarias + JaCoCo (≥ 60 % en los servicios núcleo) → cliente web → SonarQube con quality gate bloqueante → pruebas de integración |
| `main` (manual) | lo anterior → versión semver con git-cliff → 9 imágenes `vX.Y.Z` en Nexus → tag y GitHub Release → deploy dev → E2E sobre dev → deploy stage → **aprobación manual** → deploy prod |

- El quality gate `CircleGuard` evalúa el **código nuevo** (últimos 30 días): cobertura ≥ 60 %, 0 problemas bloqueantes y 0 vulnerabilidades.
- Solo `feat`, `fix`, `perf`, `refactor`, `build` o un `!` generan release. Los commits `docs`, `test`, `ci` y `chore` no generan versión.
- El build de `main` se lanza a mano (Jenkins → `circleguard` → `main` → *Build Now*) porque Nexus no permite volver a publicar una versión (`ALLOW_ONCE`).

## 6. Despliegue, ambientes y rollback

| Ambiente | VM | Región | Cómo llega una versión |
|---|---|---|---|
| dev | cg-dev | westus2 | automático en cada release de `main` |
| stage | cg-stage | chilecentral | automático si los E2E pasan en dev |
| prod | cg-prod | chilecentral | después de la aprobación manual en Jenkins |

- **Desplegar una versión:** el pipeline entra por SSH como `deploy`, exporta `VERSION` y `REGISTRY` y ejecuta `docker compose pull` y `docker compose up -d --wait` en `/opt/circleguard/<env>`. Es la misma imagen en los tres ambientes; solo cambia el `.env`. Detalle del compose en [deploy/README.md](deploy/README.md).
- **Aprobar producción:** en el build de `main`, etapa *Aprobación PROD* → *Desplegar*. Solo `luis`, `simon` o `dario`; espera 2 horas.
- **Rollback:** job `circleguard-rollback` → *Build with Parameters*:

  | Parámetro | Ejemplo |
  |---|---|
  | `TARGET_ENV` | `prod` |
  | `ROLLBACK_VERSION` | `v1.0.0` (debe existir en Nexus) |
  | `REASON` | motivo; queda en el log y en Discord |

  En prod pide aprobación. No reconstruye nada: redespliega una imagen que ya está en Nexus (~4 min).
- **Proceso de cambios:** [docs/change-management/](docs/change-management/) (plantilla de solicitud, política de aprobación por tipo de cambio y versionado).

## 7. Pruebas en local

```bash
./gradlew test                    # unitarias de los 8 servicios
./gradlew check                   # + regla de cobertura ≥ 60 % en auth, identity, promotion, form y gateway
./gradlew jacocoTestReport        # reporte en services/<svc>/build/reports/jacoco/test/html
./gradlew integrationTest         # integración con Testcontainers (requiere Docker)
./gradlew :services:circleguard-promotion-service:performanceTest   # benchmark NFR-1 (fuera del pipeline)
```

E2E con Playwright contra un ambiente: ver [tests/e2e-playwright/README.md](tests/e2e-playwright/README.md).

```bash
cd tests/e2e-playwright && npm ci && npx playwright install chromium
BASE_URL=http://20.114.42.226 npx playwright test
```

| Documento | Contenido |
|---|---|
| [docs/pruebas/cobertura.md](docs/pruebas/cobertura.md) | Cobertura por servicio y exclusiones |
| [docs/pruebas/e2e.md](docs/pruebas/e2e.md) | Los 3 flujos E2E |

Toda la aplicación en local: `docker compose -f docker-compose.dev.yml up`.

## 8. Secretos

**Ningún secreto está en el repositorio ni en los informes.**

| Secreto | Dónde vive |
|---|---|
| Contraseñas de la *middleware* y JWT de cada ambiente | `/opt/circleguard/<env>/.env` en su servidor (`chmod 600`, generado por `bootstrap-env.sh`) |
| Admin de SonarQube y Nexus, usuarios `jenkins-ci` y `deploy-pull` | `/opt/cg-tools/.env` en cg-tools (generado por `bootstrap-tools.sh`) |
| Token de GitHub, Discord, registro, Sonar y llave SSH de despliegue | Jenkins Credentials (enmascarados en los logs) |

Los archivos `.env.example` de `deploy/` y `infra/bootstrap/tools/` solo tienen
los nombres de las variables. `.env` está en `.gitignore`.

## 9. Apagar o destruir los recursos

```bash
./infra/azure/provision.sh --status                                             # estado e IPs
az vm deallocate --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)  # apagar (solo se paga disco e IP)
az vm start      --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)  # encender (las IPs no cambian)
az group delete -n circleguard-rg --yes                                         # destruir todo al terminar el curso
```

Encendidas cuestan unos USD 0,46 por hora; apagadas, unos USD 0,5 por día.

---

## Equipo y gestión

| Integrante | Responsabilidad |
|---|---|
| Luis Cadena | Gestión ágil y *branching*, empaquetamiento inmutable, *change management* |
| Simón Reyes | Pipeline Jenkins (CI) y entrega continua |
| Darío Marquínez | Infraestructura en la nube con Bash y pruebas automatizadas |

- Flujo de trabajo: [docs/agil/branching.md](docs/agil/branching.md) (GitHub Flow, Conventional Commits, *squash merge*), [Sprint 0](docs/agil/sprint-0.md) y [Sprint 1](docs/agil/sprint-1.md).
- Uso de IA generativa: [IAG_REGISTRO.md](IAG_REGISTRO.md).
