# Módulo `network`

Red de un host: VNet, subred `default`, NSG asociado a la subred e IP pública
estática (SKU Standard). Las reglas de entrada llegan por `inbound_rules`; sin
regla, el puerto queda cerrado desde Internet.

La IP pública se crea aquí, y no junto a la VM, para conocerla antes de crear
las VMs: el NSG de tools la necesita para abrir el registro al clúster, y el
certificado de la API de k3s la necesita como SAN.

| Entrada | Ejemplo |
|---|---|
| `name` | `cg-k3s` |
| `address_space` / `subnet_prefix` | `10.20.0.0/16` / `10.20.1.0/24` |
| `inbound_rules` | `[{ name = "web", priority = 110, ports = ["80","443"], sources = ["Internet"] }]` |

Salidas: `subnet_id`, `public_ip_id`, `public_ip_address`, `fqdn`.
