# Estimación de costos de infraestructura (CG-108)

Este documento estima cuánto cuesta la infraestructura de CircleGuard en Azure durante la Parte 2. Es una **estimación**: el costo real se llena después, desde *Cost Management* (sección final).

> **Todos los precios son aproximados y deben verificarse en la [calculadora oficial de Azure](https://azure.microsoft.com/pricing/calculator/)** antes de citarlos. Los valores de partida salen de la sección "Costo estimado" de [`topologia.md`](topologia.md), que a su vez los tomó de la API pública de precios (pay-as-you-go, Linux, USD).

## Supuestos

- Horas del sprint: **40 h** encendidas (estimación de [`topologia.md`](topologia.md) para la Parte 2: las VMs se apagan con `az vm deallocate` cuando no se trabaja).
- Las IPs públicas estáticas y los discos se cobran aunque la VM esté apagada. Aquí se multiplican por las mismas 40 h para ser consistentes con `topologia.md`; si se cobran 24/7 el valor sube (ver nota debajo de la tabla).
- Suscripción Azure for Students; regiones `westus2` y `chilecentral` (ver [`topologia.md`](topologia.md)).

## Recursos actuales (4 VMs, ya desplegadas)

Fuente de cantidades y tamaños: tabla "Topología desplegada" de [`topologia.md`](topologia.md).

| Recurso | Cantidad | Precio por hora (USD) | Horas | Total (USD) | Estado del precio |
|---|---:|---:|---:|---:|---|
| VM `Standard_B4s_v2` (cg-tools, westus2) | 1 | 0,166 | 40 | 6,64 | verificar en la calculadora de Azure |
| VM `Standard_B2s_v2` (cg-dev, westus2) | 1 | 0,083 | 40 | 3,32 | verificar en la calculadora de Azure |
| VM `Standard_B2s_v2` (cg-stage y cg-prod, chilecentral) | 2 | 0,096 | 40 | 7,68 | verificar en la calculadora de Azure |
| IP pública estática Standard | 4 | 0,005 | 40 | 0,80 | verificar en la calculadora de Azure |
| Discos StandardSSD (64 + 3 × 40 GB = 184 GB) | 184 GB | ≈ 0,002 (total) | 40 | ≈ 0,08 | verificar en la calculadora de Azure |
| **Subtotal actual** | | **≈ 0,46** | | **≈ 18,5** | |

Nota: IPs + discos sin apagar suman ≈ USD 0,5/día (según `topologia.md`); en un sprint de 6 días serían ≈ USD 3 aunque las VMs estén apagadas. Verificar.

## Recursos planeados (clúster k3s)

Los archivos de Terraform y del chart de Helm **no existen en esta rama** (`infra/` solo tiene `azure/` y `bootstrap/`; `deploy/` no tiene `helm`). Por eso no hay evidencia de cuántos nodos ni de qué tamaño tendrá el clúster. Las filas siguientes son plantillas para completar cuando Darío publique `infra/terraform`.

| Recurso | Cantidad | Precio por hora (USD) | Horas | Total (USD) | Estado del precio |
|---|---:|---:|---:|---:|---|
| Nodos k3s (VM, tamaño por definir) | pendiente de evidencia | verificar en la calculadora de Azure | pendiente | pendiente | verificar en la calculadora de Azure |
| Discos de los nodos | pendiente de evidencia | verificar en la calculadora de Azure | pendiente | pendiente | verificar en la calculadora de Azure |
| IP pública / balanceador del Ingress | pendiente de evidencia | verificar en la calculadora de Azure | pendiente | pendiente | verificar en la calculadora de Azure |
| Almacenamiento persistente (volúmenes) | pendiente de evidencia | verificar en la calculadora de Azure | pendiente | pendiente | verificar en la calculadora de Azure |
| **Subtotal planeado** | | | | pendiente | |

Si el clúster **reemplaza** a las VMs de compose, el total final es el subtotal planeado; si **convive** con ellas, se suma al subtotal actual. Decisión pendiente de evidencia.

## Costo real

Se llena después de la evaluación con *Cost Management*, filtrando por el tag `project=circleguard` (pendiente de evidencia de que ese tag esté aplicado a los recursos).

| Concepto | Valor |
|---|---|
| Rango de fechas consultado | |
| Costo real total (USD) | |
| Costo estimado (USD) | |
| Diferencia y explicación | |
| Captura de Cost Management | |
