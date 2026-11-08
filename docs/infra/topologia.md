# Topología, tamaño y costo de la infraestructura (CG-30)

La infraestructura se crea con un comando (`infra/azure/provision.sh`) y cada
servidor se configura con otro (`infra/bootstrap/bootstrap-tools.sh` y
`bootstrap-env.sh <dev|stage|prod>`). Todos son idempotentes.

## Restricción de la cuenta

Usamos **Azure for Students**:

- Solo permite las regiones `westus2`, `northcentralus`, `chilecentral`, `brazilsouth` y `spaincentral` (política de la suscripción).
- Limita a **6 vCPU por región** (familia B: 4; DSv3: 4).
- La familia `Basv2` (B AMD) no está disponible para la suscripción.

Con 6 vCPU no caben herramientas (16 GB) más tres ambientes de 8 GB en una sola
región, así que los servidores se reparten en **dos regiones de EE. UU.** Son las
más baratas permitidas; Brasil y Chile cuestan entre 30 y 60 % más.

## Topología

```
                         Internet (GitHub webhook, equipo, profesor)
                                        │
   northcentralus ─────────────────────────────┼────────────────    westus2 ───────────────────────
   ┌────────────────────────────┐   ┌───┴──────────────┐      ┌──────────────────┐ ┌──────────────────┐
   │ cg-tools  B4ms 4 vCPU/16GB │SSH│ cg-dev  D2s_v3   │      │ cg-stage  B2ms   │ │ cg-prod   B2ms   │
   │ Jenkins   :8080 (Internet) │──►│ 2 vCPU / 8 GB    │      │ 2 vCPU / 8 GB    │ │ 2 vCPU / 8 GB    │
   │ SonarQube :9000 (equipo)   │──────────────────────────►  │                  │ │                  │
   │ Nexus     :8081 (equipo)   │   │ compose dev      │      │ compose stage    │ │ compose prod     │
   │ Registro  :8082 (ambientes)│◄──┤ núcleo+mw+web :80│      │ núcleo+mw+web :80│ │ núcleo+mw+web :80│
   └────────────────────────────┘   └──────────────────┘      └──────────────────┘ └──────────────────┘
```

**Ambientes aislados:** cada uno tiene su propia VM, NSG, usuario `deploy`,
carpeta `/opt/circleguard/<env>`, proyecto de compose y `.env` con secretos
generados en ese servidor.

## Tamaño (justificación)

| Servidor | Carga | Memoria estimada | Elegido |
|---|---|---|---|
| cg-tools | SonarQube con Elasticsearch (2–3 GB), Jenkins (1 GB de heap), Nexus (1 GB de heap y 1 GB directo), builds de Gradle (2–3 GB) y Testcontainers de las pruebas de integración (Kafka, Neo4j, Postgres y Redis, unos 2 GB) | 10–12 GB | **B4ms** (4 vCPU, 16 GB) + 4 GB de swap |
| cg-dev / stage / prod | 5 servicios Spring núcleo (unos 400 MB c/u) más web, Neo4j (1 GB), Kafka con Zookeeper (1 GB), PostgreSQL, Redis y OpenLDAP (unos 0,5 GB) | 4,5–5,5 GB | **D2s_v3 / B2ms** (2 vCPU, 8 GB) + 4 GB de swap |

La familia B (*burstable*) acumula créditos de CPU mientras está ociosa y los
gasta en los picos de un build o un despliegue. Es el perfil de un pipeline
académico. `cg-dev` usa D2s_v3 porque la cuota de la familia B (4 vCPU) ya la
ocupa `cg-tools` en `northcentralus`.

**Por qué tools está en northcentralus:** el plan original era `westus2`, pero el
7 de noviembre Azure rechazó la B4ms ahí por falta de capacidad
(`SkuNotAvailable: ... failed for Capacity Restrictions`). Las dos regiones
cuestan lo mismo para estos tamaños, así que se invirtieron.

## Red: mínimo privilegio (NSG)

| VM | Puerto | Origen | Por qué |
|---|---|---|---|
| tools | 22 | IP del equipo | administración |
| tools | 8080 | Internet | webhook de GitHub hacia Jenkins |
| tools | 9000, 8081 | IP del equipo | UI de SonarQube y Nexus |
| tools | 8082 | IPs públicas de dev, stage, prod y tools | `docker pull/push` al registro, nadie más |
| dev, stage, prod | 22 | **solo la IP de tools** (Jenkins) e IP del equipo | despliegue por SSH |
| dev, stage, prod | 80 | Internet | cliente web (E2E desde Jenkins y demostración) |

Los backends no publican puertos. El navegador solo habla con nginx (`:80`), que
reenvía a cada servicio por la red interna de Docker.

**Trade-off por usar dos regiones:** los ambientes descargan las imágenes por la
IP pública de `cg-tools` y el registro va por **HTTP** con autenticación. El
riesgo se acota con el NSG (solo 4 IPs pueden llegar al 8082), usuarios de
mínimo privilegio en Nexus (`jenkins-ci` hace push, `deploy-pull` solo lee) y
`ALLOW_ONCE` (un tag no se puede reemplazar). En la Parte 2 se agrega TLS (E2.6).

## Costo estimado

Precios *pay-as-you-go* Linux de la API pública de precios de Azure, en USD por hora:

| Recurso | Cantidad | USD/h | USD/día (24 h) |
|---|---:|---:|---:|
| B4ms (northcentralus) | 1 | 0,166 | 3,98 |
| D2s_v3 (northcentralus) | 1 | 0,096 | 2,30 |
| B2ms (westus2) | 2 | 0,083 | 3,99 |
| IP pública estática Standard | 4 | 0,005 | 0,48 |
| Disco StandardSSD (64 + 3 × 40 GB) | 184 GB | ≈ 0,002 | ≈ 0,05 |
| **Total** | | **≈ 0,43** | **≈ 10,8** |

| Escenario | Horas | Costo |
|---|---:|---:|
| Parte 1 (del sáb 7 en la noche al dom 8 a las 23:59) | ≈ 30 | **≈ USD 13** |
| Parte 2, encendidas solo mientras se trabaja (≈ 40 h) | ≈ 40 | ≈ USD 17 |
| Apagadas (`az vm deallocate`): solo discos e IPs | — | ≈ USD 0,5/día |

No se usan las 730 h/mes: las VMs se apagan cuando no se trabaja. El costo
**real** se toma del portal (*Cost Management*) para compararlo en la Parte 2 (E2.9).

## Operación

```bash
./infra/azure/provision.sh --status                      # IPs y estado
az vm deallocate --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)   # apagar
az vm start      --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)   # encender
az group delete -n circleguard-rg --yes                  # destruir todo al final del curso
```

Las IPs públicas son **estáticas**: no cambian al apagar y encender, así que las
variables `CG_*_HOST` de Jenkins siguen siendo válidas.
