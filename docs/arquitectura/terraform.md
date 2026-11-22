# Infraestructura de Terraform (Parte 2)

Fuente: [`infra/terraform`](../../infra/terraform) (`envs/shared`, `envs/{dev,stage,prod}`,
`modules/*`, `bootstrap/state.sh`). Vista dentro del clúster en
[`kubernetes.md`](kubernetes.md).

```mermaid
flowchart LR
  subgraph TFS["cg-tfstate-rg · bootstrap/state.sh (fuera de los ambientes)"]
    ST[("Storage Account cgtfstate…<br/>contenedor tfstate<br/>circleguard/&lt;env&gt;.tfstate<br/>solo Azure AD, versionado")]
  end

  subgraph SH["circleguard-rg · envs/shared"]
    subgraph NET1["westus2 · módulo network<br/>VNet 10.20.0.0/16 · subred 10.20.1.0/24"]
      NSG1["NSG: 22 admin · 6443 (tools y admin)<br/>80/443 Internet"]
      IP1["IP pública"]
      VM1["módulo cluster · cg-k3s<br/>E4as_v5 · k3s + cloud-init<br/>namespaces dev/stage/prod/observability/cicd"]
    end
    subgraph NET2["westcentralus · módulo network<br/>VNet 10.30.0.0/16 · subred 10.30.1.0/24"]
      NSG2["NSG: 22 admin · 8080 Internet<br/>9000/8081 admin · 8082 (clúster, tools, admin)"]
      IP2["IP pública"]
      VM2["módulo registry · cg-tools<br/>B4s_v2 · Jenkins, Sonar, Nexus (registro Docker)"]
    end
    OBS["módulo observability<br/>grupo de acciones, presupuesto mensual,<br/>alerta de VM caída"]
  end

  subgraph DEVRG["cg-dev-rg · envs/dev"]
    KVD["módulo secrets: Key Vault + 7 secretos generados"]
    BKD[("módulo storage: cuenta de respaldos")]
  end
  subgraph STGRG["cg-stage-rg · envs/stage"]
    KVS["Key Vault + secretos"]
    BKS[("cuenta de respaldos")]
  end
  subgraph PRDRG["cg-prod-rg · envs/prod"]
    KVP["Key Vault + secretos"]
    BKP[("cuenta de respaldos")]
  end

  ST -. "backend azurerm" .- SH & DEVRG & STGRG & PRDRG
  IP1 --- NSG1 --- VM1
  IP2 --- NSG2 --- VM2
  VM2 -- "API 6443" --> VM1
  VM1 -- "pull imágenes :8082" --> VM2
  OBS -. "monitorea" .-> VM1 & VM2
  KVD & KVS & KVP -- "deploy/helm/create-secrets.sh" --> VM1
```

## Qué aplica cada raíz

| Raíz | Módulos que llama | La aplica |
|---|---|---|
| `envs/shared` | `network` (×2), `cluster`, `registry`, `observability` | Administrador (una vez) |
| `envs/dev`, `stage`, `prod` | `secrets`, `storage` | Jenkins: plan en PR, apply en `main` (prod con aprobación) |

## Correspondencia con el código

| Elemento del diagrama | Dónde |
|---|---|
| Dos VNet, subredes, NSG, IP pública | `modules/network/main.tf`; rangos y reglas en `envs/shared/main.tf` |
| VM del clúster k3s | `modules/cluster` (`cloud-init.yaml.tftpl`) |
| VM de herramientas (módulo llamado `registry`) | `modules/registry/main.tf` |
| Presupuesto y alerta de VM caída | `modules/observability/main.tf` |
| Key Vault y secretos | `modules/secrets/main.tf` |
| Respaldos | `modules/storage/main.tf` |
| Backend remoto | `envs/*/backend.tf`, `bootstrap/state.sh` |

## Notas

- Regiones, tamaños de VM y la cuenta del estado salen del README de Terraform y de
  `envs/shared`; los nombres exactos de `cg-<env>-rg` provienen del README
  (`cg-<env>-rg`) y no se revisaron en `envs/<env>/main.tf` línea por línea
  (**pendiente de evidencia**).
- El "registro" es Nexus (puerto 8082) en la VM `cg-tools`; no hay Azure Container
  Registry en el Terraform.
- Imagen PNG exportada: **pendiente** (no hay `mmdc` en este entorno).
