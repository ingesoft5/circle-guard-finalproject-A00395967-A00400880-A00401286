# Topología, tamaño y costo de la infraestructura (CG-30)

Todo se crea y configura con scripts idempotentes:

| Paso | Comando | Qué hace |
|---|---|---|
| 1 | `./infra/azure/provision.sh` | grupo de recursos, VNets, IPs estáticas, NSG y las 4 VMs |
| 2 | `./infra/azure/bootstrap-all.sh` | copia el repo a cada VM y ejecuta `bootstrap-tools.sh` y `bootstrap-env.sh <dev\|stage\|prod>` |

Ejecutar cualquiera de los dos por segunda vez no cambia nada y termina con
código 0. La salida de esa segunda ejecución está en [`evidencia/`](evidencia/)
(CG-34).

## Restricciones de la cuenta (Azure for Students)

- **Regiones permitidas** (política de la suscripción): `westus2`, `northcentralus`, `chilecentral`, `brazilsouth` y `spaincentral`.
- **Cuota:** 6 vCPU por región en total y 4 por familia (10 en la familia Bsv2).
- **Capacidad:** el 7 de noviembre Azure rechazó la mayoría de tamaños con `SkuNotAvailable (Capacity Restrictions)` aunque había cuota. B4ms y la serie D/E fallaron en `westus2` y `northcentralus`. Antes de elegir, se sondeó cada región y tamaño con `infra/azure/probe-capacity.sh`, que valida una plantilla ARM **sin crear nada**:

| Región | Con capacidad para esta suscripción |
|---|---|
| westus2 | B2s_v2, B4s_v2 |
| chilecentral | B2s_v2, B4s_v2 |
| spaincentral | B2s_v2, B4s_v2 |
| brazilsouth | D2as_v4, D4as_v4, E2as_v4 |
| northcentralus | ninguno de los probados |

Por eso se usa la familia **Bsv2** (Intel, *burstable*, segunda generación).
Cada VM de `provision.sh` tiene una lista de tamaños equivalentes en orden de
preferencia, para que el script siga funcionando si la capacidad cambia.

## Topología desplegada

```
                              Internet (GitHub webhook, equipo, profesor)
                                              │
 westus2 ─────────────────────────────────────┼───────────          chilecentral ─────────────────────────────
 ┌─────────────────────────────────┐   ┌──────┴───────────┐        ┌──────────────────┐ ┌──────────────────┐
 │ cg-tools   B4s_v2  4 vCPU/16 GB │SSH│ cg-dev  B2s_v2   │        │ cg-stage B2s_v2  │ │ cg-prod  B2s_v2  │
 │ Jenkins   :8080   (Internet)    │──►│ 2 vCPU / 8 GB    │        │ 2 vCPU / 8 GB    │ │ 2 vCPU / 8 GB    │
 │ SonarQube :9000   (equipo)      │─────────────────────────────► │                  │ │                  │
 │ Nexus     :8081   (equipo)      │   │ compose dev  :80 │        │ compose stage :80│ │ compose prod  :80│
 │ Registro  :8082   (4 VMs)       │◄──┴──────────────────┴────────┴──────────────────┴─┴──────────────────┘
 └─────────────────────────────────┘         docker pull (IP pública, solo las 4 VMs)
```

| VM | Región | Tamaño | IP pública (estática) |
|---|---|---|---|
| cg-tools | westus2 | Standard_B4s_v2 (4 vCPU, 16 GB) | 20.230.211.66 |
| cg-dev | westus2 | Standard_B2s_v2 (2 vCPU, 8 GB) | 20.114.42.226 |
| cg-stage | chilecentral | Standard_B2s_v2 (2 vCPU, 8 GB) | 57.156.56.122 |
| cg-prod | chilecentral | Standard_B2s_v2 (2 vCPU, 8 GB) | 57.156.59.208 |

Las IPs son estáticas: no cambian al apagar y encender. `./infra/azure/provision.sh --status` las muestra.

**Ambientes aislados:** cada uno tiene su propia VM, NSG, usuario `deploy`,
carpeta `/opt/circleguard/<env>`, proyecto de compose y `.env` con secretos
generados **en ese servidor** (nunca en git). Stage y prod quedan además en otra
región que dev, más cerca de Colombia.

## Tamaño (justificación)

| Servidor | Carga | Memoria estimada | Elegido |
|---|---|---|---|
| cg-tools | SonarQube con Elasticsearch (2–3 GB), Jenkins (1 GB de heap), Nexus (1 GB de heap y 1 GB directo), builds de Gradle (2–3 GB) y Testcontainers de las pruebas de integración (Kafka, Neo4j, Postgres y Redis, unos 2 GB) | 10–12 GB | **B4s_v2** (4 vCPU, 16 GB) |
| cg-dev / stage / prod | 5 servicios Spring núcleo (unos 400 MB c/u) más web, Neo4j (1 GB), Kafka con Zookeeper (1 GB), PostgreSQL, Redis y OpenLDAP (unos 0,5 GB) | 4,5–5,5 GB | **B2s_v2** (2 vCPU, 8 GB) + 4 GB de swap |

La familia B acumula créditos de CPU mientras está ociosa y los gasta en los
picos de un build o un despliegue. Es el perfil de un pipeline académico.

## Red: mínimo privilegio (NSG)

| VM | Puerto | Origen | Por qué |
|---|---|---|---|
| tools | 22 | IP del equipo | administración |
| tools | 8080 | Internet | webhook de GitHub hacia Jenkins |
| tools | 9000, 8081 | IP del equipo | UI de SonarQube y Nexus |
| tools | 8082 | IPs públicas de dev, stage, prod y tools | `docker pull/push` al registro, nadie más |
| dev, stage, prod | 22 | **solo la IP de tools** (Jenkins) e IP del equipo | despliegue por SSH |
| dev, stage, prod | 80 | Internet | cliente web (E2E desde Jenkins y demostración) |

Los backends no publican puertos. El navegador solo habla con nginx (`:80`),
que reenvía a cada servicio por la red interna de Docker. El NSG es el
perímetro real; `ufw` es una segunda capa en el host. Los puertos que publica
Docker no pasan por `ufw`.

**Trade-off por usar dos regiones:** los ambientes descargan las imágenes por
la IP pública de `cg-tools` y el registro va por **HTTP** con autenticación. El
riesgo se acota con:

- el NSG: solo 4 IPs llegan al 8082;
- usuarios de mínimo privilegio en Nexus: `jenkins-ci` hace push y `deploy-pull` solo lee;
- `writePolicy: ALLOW_ONCE`: un tag no se puede reemplazar.

Las tres cosas se probaron: el push de `jenkins-ci` funciona, sobrescribir
`v0.0.1` se rechaza, el pull desde stage (Chile) funciona y el push de
`deploy-pull` se rechaza. En la Parte 2 se agrega TLS (E2.6).

## Costo estimado

Precios *pay-as-you-go* Linux en USD por hora (API pública de precios de
Azure). Chile cuesta alrededor de un 15 % más que EE. UU.

| Recurso | Cantidad | USD/h aprox. | USD/día (24 h) |
|---|---:|---:|---:|
| B4s_v2 (westus2) | 1 | 0,166 | 3,98 |
| B2s_v2 (westus2) | 1 | 0,083 | 2,00 |
| B2s_v2 (chilecentral) | 2 | 0,096 | 4,61 |
| IP pública estática Standard | 4 | 0,005 | 0,48 |
| Disco StandardSSD (64 + 3 × 40 GB) | 184 GB | ≈ 0,002 | ≈ 0,05 |
| **Total** | | **≈ 0,46** | **≈ 11,1** |

| Escenario | Horas | Costo |
|---|---:|---:|
| Parte 1 (del sáb 7 en la noche al dom 8 a las 23:59) | ≈ 26 | **≈ USD 12** |
| Parte 2, encendidas solo mientras se trabaja | ≈ 40 | ≈ USD 18 |
| Apagadas (`az vm deallocate`): solo discos e IPs | — | ≈ USD 0,5/día |

No se usan las 730 h/mes: las VMs se apagan cuando no se trabaja. El costo
**real** se toma del portal (*Cost Management*) para compararlo en la Parte 2 (E2.9).

## Operación

```bash
./infra/azure/provision.sh --status                                             # IPs y estado
az vm deallocate --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)  # apagar
az vm start      --ids $(az vm list -g circleguard-rg --query "[].id" -o tsv)  # encender
az group delete -n circleguard-rg --yes                                         # destruir todo al final del curso
```
