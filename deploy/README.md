# Despliegue con Docker Compose (dev, stage, prod)

Archivos de despliegue de CircleGuard por ambiente (CG-22, CG-23). Los usa el
pipeline (`jenkins/vars/cgDeploy.groovy`) por SSH y también sirven para desplegar
a mano.

```
deploy/
├── docker-compose.yml               # base común: middleware, 8 servicios y web
├── dev/
│   ├── docker-compose.override.yml  # puertos de depuración + logs DEBUG + Kafka externo
│   └── .env.example                 # plantilla del .env de dev
├── stage/
│   ├── docker-compose.override.yml  # vacío a propósito: igual a prod
│   └── .env.example
└── prod/
    ├── docker-compose.override.yml  # vacío a propósito: mismos límites que la base
    └── .env.example
```

## Servidores

| Ambiente | VM (Azure) | IP pública | Cliente web |
|---|---|---|---|
| dev | cg-dev, B2s_v2 (2 vCPU, 8 GB) | 20.114.42.226 | http://20.114.42.226 |
| stage | cg-stage, B2s_v2 (2 vCPU, 8 GB) | 57.156.56.122 | http://57.156.56.122 |
| prod | cg-prod, B2s_v2 (2 vCPU, 8 GB) | 57.156.59.208 | http://57.156.59.208 |
| herramientas | cg-tools, B4s_v2 (4 vCPU, 16 GB) | 20.230.211.66 | Jenkins :8080 · registro Nexus **20.230.211.66:8082** |

- En dev, stage y prod solo el puerto 80 (`WEB_PORT`) está abierto a Internet.
- **El SSH (22) solo se permite desde cg-tools**, donde corre Jenkins. Para
  operar a mano, primero hay que entrar a cg-tools y desde allí al servidor del
  ambiente.

## Cómo funciona

- `docker compose` sin `-f` lee `docker-compose.yml`, le fusiona
  `docker-compose.override.yml` si existe y toma las variables de `.env`, todo
  del directorio actual. Por eso el servidor tiene los tres archivos juntos.
- Todas las imágenes usan la misma `${VERSION}` (`vX.Y.Z`, nunca `latest`). El
  pipeline exporta `VERSION` y `REGISTRY` (desde `CG_REGISTRY`) antes de
  `docker compose`. Las variables exportadas en el shell tienen prioridad sobre
  `.env`, así que **`VERSION` no va en `.env`**.
- Variables:
  - Los **secretos** son obligatorios (`${X:?...}`). Si falta uno, compose se
    detiene con un mensaje claro en lugar de desplegar algo a medias.
  - Lo que **no es secreto** tiene un valor por defecto (`${X:-valor}`).
  - `CG_ENV`, `REGISTRY` y `VERSION` también son obligatorias, porque no tienen
    un valor por defecto seguro.
- Los servicios Spring Boot se configuran con variables de entorno (relaxed
  binding: `SPRING_DATASOURCE_URL` sobreescribe `spring.datasource.url`). Los
  `application.yml` siguen apuntando a `localhost` para el desarrollo local.
- `up --wait` espera a que cada contenedor quede `healthy`. El pipeline usa
  `--wait-timeout 300`. Los healthchecks son:
  - Java: `/actuator/health/readiness` (`start_period` 120s).
  - web: `/healthz`.
  - postgres: `pg_isready`.
  - redis: `redis-cli ping`.
  - kafka: `kafka-topics --list`.
  - neo4j: HTTP en 7474.
  - openldap: `ldapsearch`.
- Los `depends_on` con `service_healthy` siguen las dependencias reales de cada
  `application.yml` y del código:

  | Servicio | Espera a |
  |---|---|
  | auth-service | postgres, openldap |
  | identity-service | postgres, kafka |
  | promotion-service | postgres, neo4j, kafka, redis |
  | notification-service | kafka |
  | form-service | postgres, kafka |
  | file-service | — |
  | gateway-service | redis |
  | dashboard-service | postgres |
  | web | los 8 servicios |

- Solo `web` publica un puerto (`WEB_PORT`, 80 por defecto). nginx hace proxy de
  `/svc/<servicio>/` a los backends por la red interna. En dev, además, se
  publican backends y middleware en `DEV_BIND_ADDRESS` (127.0.0.1 por defecto:
  se accede con túnel SSH).
- El proyecto se llama `circleguard-<CG_ENV>`, así que dos ambientes en el mismo
  servidor no chocarían (con un `WEB_PORT` distinto).

## Memoria: presupuesto para una VM de 8 GB

Cada contenedor tiene `mem_limit`, igual en los tres ambientes. La suma de los
límites es un **techo**: el consumo real es menor (ver la medición abajo).

| Contenedor | Límite | Variable para ajustarlo |
|---|---:|---|
| 8 servicios Java × 512 MiB | 4096 MiB | `JAVA_MEM_LIMIT` |
| neo4j (heap 384m + pagecache 128m + nativa) | 1024 MiB | `NEO4J_MEM_LIMIT`, `NEO4J_HEAP_MAX`, `NEO4J_PAGECACHE` |
| kafka (heap 384m) | 768 MiB | `KAFKA_MEM_LIMIT` |
| postgres (≈ 50 conexiones de Hikari) | 384 MiB | `POSTGRES_MEM_LIMIT` |
| zookeeper (heap 128m) | 256 MiB | — |
| redis | 128 MiB | — |
| openldap | 128 MiB | — |
| web (nginx) | 64 MiB | — |
| **Total** | **6848 MiB ≈ 6,7 GiB** | |

- **Margen:** la VM tiene 8 GiB (unos 7,7 GiB visibles para el sistema). Quedan
  ≈ 1 GiB para Ubuntu, dockerd y containerd. Además, `bootstrap-env.sh` crea
  4 GiB de swap para los picos de arranque.
- **Neo4j:** tiene 1 GiB porque en reposo y sin datos ya usaba ≈ 680 MiB. Con
  768 MiB quedaba al 89 % y en riesgo de OOM kill.
- **JVM:** las imágenes Java arrancan con `-XX:MaxRAMPercentage=75`, así que el
  heap es 75 % del límite (≈ 384 MiB con 512m).
- **Heaps fijos:** Kafka (`-Xmx1G` por defecto) y Zookeeper (`-Xmx512M`) tienen
  el heap fijado para que quepan en su límite. El healthcheck de Kafka arranca
  su propia JVM con `-Xmx64m`, para no sumar otro heap de 384 MiB dentro del
  mismo contenedor.

Medición en local (Docker Desktop con 8 GB, dev completo recién arrancado):
ver [`docs/evidencia/compose-local-dev.txt`](../docs/evidencia/compose-local-dev.txt).

## Estructura en el servidor

```
/opt/circleguard/
├── init-db.sql                      # compartido; el compose lo monta como ../init-db.sql
└── <ambiente>/                      # dev | stage | prod (dueño: deploy)
    ├── docker-compose.yml           # copia de deploy/docker-compose.yml
    ├── docker-compose.override.yml  # copia de deploy/<ambiente>/docker-compose.override.yml
    ├── .env                         # secretos reales (chmod 600, NUNCA en git)
    └── .deployed-version            # lo escribe cgDeploy tras cada despliegue
```

## Lo que hace `bootstrap-env.sh`

Lo hace `infra/bootstrap/bootstrap-env.sh <env>`, de Darío:

- Copia `docker-compose.yml`, el override del ambiente e `init-db.sql` a la
  estructura de arriba. Si `init-db.sql` faltara, Docker crearía un
  **directorio** con ese nombre y postgres no crearía las bases.
- Crea el `.env` **solo si no existe**:
  - Parte de `deploy/<env>/.env.example`.
  - Genera los secretos con `openssl rand -hex 32`.
  - Reafirma `CG_ENV`, `REGISTRY` y `WEB_PORT` en cada ejecución.
- Hace `docker login` de solo lectura (`deploy-pull`) contra Nexus y registra
  Nexus como `insecure-registries`, porque va por HTTP.

Variables que exige el compose frente a las que genera bootstrap:

| Variable del compose | ¿La genera bootstrap? | Si no está en `.env` |
|---|---|---|
| `CG_ENV`, `REGISTRY`, `WEB_PORT` | sí (y el pipeline exporta `REGISTRY`) | — |
| `POSTGRES_PASSWORD`, `NEO4J_PASSWORD`, `LDAP_ADMIN_PASSWORD` | sí | compose falla |
| `JWT_SECRET`, `QR_SECRET`, `VAULT_SECRET`, `VAULT_SALT` | sí | compose falla |
| `LDAP_CONFIG_PASSWORD` | no | usa `LDAP_ADMIN_PASSWORD` |
| `VAULT_HASH_SALT` | no | usa `VAULT_SALT` |
| `POSTGRES_USER` | no | `circleguard` |
| `*_MEM_LIMIT`, `NEO4J_HEAP_MAX`, `NEO4J_PAGECACHE` | no | valores de la tabla de memoria |

Nunca se debe sobrescribir un `.env` existente ni agregarle `VAULT_HASH_SALT`
después de tener datos:

- Cambiar `VAULT_SECRET` o `VAULT_SALT` deja ilegibles las identidades cifradas.
- Cambiar el hash salt hace que ya no se encuentren las identidades.
- Cambiar las contraseñas de postgres, neo4j o ldap no las cambia en volúmenes
  ya inicializados.

## Desplegar a mano

Desde cg-tools, por SSH al servidor del ambiente:

```bash
cd /opt/circleguard/<ambiente>
export VERSION=v1.2.3
export REGISTRY=20.230.211.66:8082   # opcional: ya está en .env
docker compose pull --quiet
docker compose up -d --remove-orphans --wait --wait-timeout 300
echo "$VERSION" > .deployed-version
docker compose ps
```

`docker compose ps`, `logs` y `down` también interpolan el archivo, así que piden
`VERSION`. Para operar sobre lo que ya está desplegado:

```bash
export VERSION=$(cat .deployed-version)
docker compose logs -f auth-service
```

## Rollback

Las imágenes son inmutables en Nexus (`ALLOW_ONCE`): volver atrás es desplegar
la versión anterior.

```bash
cd /opt/circleguard/<ambiente>
export VERSION=<anterior> && docker compose up -d --wait
echo "$VERSION" > .deployed-version
```

Es lo mismo que hace el job `jenkins/Jenkinsfile.rollback` (con aprobación para
prod). El esquema de base de datos no se revierte:

- auth, identity, promotion, form y dashboard migran con Flyway, que solo
  avanza, y además tienen `ddl-auto: update`.
- Por eso las migraciones deben ser compatibles con la versión anterior
  (expand/contract).

## Probar en local

Con las imágenes construidas y retagueadas como `local/circleguard/<x>:v0.0.1`:

```bash
cd deploy
cp dev/.env.example /tmp/cg-dev.env
CG_ENV=dev REGISTRY=local VERSION=v0.0.1 docker compose \
  -f docker-compose.yml -f dev/docker-compose.override.yml \
  --env-file /tmp/cg-dev.env up -d --wait --wait-timeout 600
curl -s http://localhost/healthz
curl -s http://localhost/svc/auth/actuator/health/readiness
# Al terminar (borra también los volúmenes):
CG_ENV=dev REGISTRY=local VERSION=v0.0.1 docker compose \
  -f docker-compose.yml -f dev/docker-compose.override.yml \
  --env-file /tmp/cg-dev.env down -v
```

El override de dev publica 5432, 7474, 7687, 9092, 6379, 389 y los puertos de
los servicios en 127.0.0.1. Si alguno ya está ocupado en el equipo, `up` falla
con `port is already allocated`.

Solo validar la sintaxis de un ambiente:

```bash
cd deploy
CG_ENV=prod REGISTRY=local VERSION=v0.0.1 docker compose \
  -f docker-compose.yml -f prod/docker-compose.override.yml \
  --env-file prod/.env.example config -q
```

## Variables del `.env`

| Variable | Usada por | Notas |
|---|---|---|
| `CG_ENV` | nombre del proyecto | `dev`, `stage` o `prod` |
| `REGISTRY` | todas las imágenes | `20.230.211.66:8082` |
| `WEB_PORT` | web | puerto publicado en el servidor (80) |
| `POSTGRES_PASSWORD` | postgres, auth, identity, promotion, form, dashboard | `POSTGRES_USER` opcional (`circleguard`) |
| `NEO4J_PASSWORD` | neo4j, promotion | usuario fijo `neo4j`; 8+ caracteres |
| `LDAP_ADMIN_PASSWORD` | openldap, auth | admin `cn=admin,dc=circleguard,dc=edu`; `LDAP_CONFIG_PASSWORD` opcional |
| `JWT_SECRET` | auth, identity, promotion, notification, gateway | 32+ caracteres, el mismo en todos |
| `QR_SECRET` | auth, gateway, notification | 32+ caracteres |
| `VAULT_SECRET` | identity | `openssl rand -hex 32`; no cambiar con datos |
| `VAULT_SALT` | identity | **hexadecimal**: `openssl rand -hex 32`; no cambiar con datos |
| `VAULT_HASH_SALT` | identity | opcional; sin ella se usa `VAULT_SALT` |
| `*_MEM_LIMIT`, `NEO4J_HEAP_MAX`, `NEO4J_PAGECACHE` | ver tabla de memoria | opcionales |
| `SMTP_*`, `GOTIFY_*`, `TWILIO_*` | notification | opcionales; sin ellos queda en modo mock |
| `DEV_BIND_ADDRESS`, `KAFKA_EXTERNAL_HOST` | solo dev | puertos de depuración |

## Limitaciones conocidas

- **LDAP fijo en el código:** `auth-service` (`SecurityConfig.contextSource()`)
  usa `ldap://localhost:389` y la contraseña `admin`. Dentro de los contenedores,
  el login por LDAP falla hasta que el código lea `spring.ldap.*`. El compose ya
  define `SPRING_LDAP_*`.
- **URL de identity:** el compose ya pasa `CIRCLEGUARD_IDENTITYSERVICE_URL`.
  Funcionará cuando `IdentityClient` lea esa propiedad. Hoy usa
  `http://localhost:8083` fijo, y el login responde 500 (`Connection refused`).
- **Archivos de file-service:** se guardan en `/tmp/uploads` (`working_dir: /tmp`)
  y se pierden al recrear el contenedor. `FileStorageService` usa la ruta
  relativa `uploads`, y `/app` es de root en la imagen (usuario 10001). El
  arreglo es crear `/app/uploads` con dueño 10001 en el Dockerfile y montar un
  volumen.
