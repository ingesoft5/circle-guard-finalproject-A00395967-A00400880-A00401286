# Plataforma compartida por los tres ambientes: el clúster k3s (un namespace
# por ambiente), la VM tools (Jenkins, SonarQube, Nexus) y el monitoreo de
# Azure. Lo propio de cada ambiente (Key Vault y respaldos) está en envs/<env>.
#
# Regiones: la cuenta gratuita da 4 vCPU por región, así que cada VM va en una
# región distinta con capacidad comprobada (infra/azure/probe-capacity.sh, 21/11/2026).

locals {
  tags = { project = "circleguard", env = "shared", managed_by = "terraform" }

  cluster = {
    name     = "cg-k3s"
    location = "westus2"
    vm_size  = "Standard_E4as_v5" # 4 vCPU / 32 GB
    disk_gb  = 128
  }
  tools = {
    name     = "cg-tools"
    location = "westcentralus"
    vm_size  = "Standard_B4s_v2" # 4 vCPU / 16 GB, igual que la Parte 1
    disk_gb  = 64
  }

  k3s_version = "v1.35.10+k3s1"
  namespaces = {
    "circleguard-dev"   = "dev"
    "circleguard-stage" = "stage"
    "circleguard-prod"  = "prod"
    "observability"     = "observability"
  }

  ssh_public_key = trimspace(file(pathexpand(var.ssh_public_key_path)))
  tools_ip       = "${module.tools_network.public_ip_address}/32"
  cluster_ip     = "${module.cluster_network.public_ip_address}/32"
}

# Mismo nombre que en la Parte 1: infra/azure/bootstrap-all.sh busca aquí cg-tools-ip.
resource "azurerm_resource_group" "this" {
  name     = "circleguard-rg"
  location = local.cluster.location
  tags     = local.tags
}

# --- Red ------------------------------------------------------------------------
module "cluster_network" {
  source              = "../../modules/network"
  name                = local.cluster.name
  location            = local.cluster.location
  resource_group_name = azurerm_resource_group.this.name
  address_space       = "10.20.0.0/16"
  subnet_prefix       = "10.20.1.0/24"
  tags                = local.tags

  inbound_rules = [
    { name = "ssh-admin", priority = 100, ports = ["22"], sources = [var.admin_cidr] },
    # API de Kubernetes: solo Jenkins (tools) y el administrador.
    { name = "k8s-api", priority = 110, ports = ["6443"], sources = [local.tools_ip, var.admin_cidr] },
    # Ingress (Traefik): el web y Grafana. Los backends no se publican.
    { name = "ingress", priority = 120, ports = ["80", "443"], sources = ["Internet"] },
  ]
}

module "tools_network" {
  source              = "../../modules/network"
  name                = local.tools.name
  location            = local.tools.location
  resource_group_name = azurerm_resource_group.this.name
  address_space       = "10.30.0.0/16"
  subnet_prefix       = "10.30.1.0/24"
  tags                = local.tags

  inbound_rules = [
    { name = "ssh-admin", priority = 100, ports = ["22"], sources = [var.admin_cidr] },
    # Webhook de GitHub hacia Jenkins.
    { name = "jenkins", priority = 110, ports = ["8080"], sources = ["Internet"] },
    { name = "sonar-nexus-ui", priority = 120, ports = ["9000", "8081"], sources = [var.admin_cidr] },
    # Registro Docker: el clúster baja imágenes y Jenkins (en esta VM) las sube por la IP pública.
    { name = "registry", priority = 130, ports = ["8082"], sources = [local.cluster_ip, local.tools_ip, var.admin_cidr] },
  ]
}

# --- Cómputo --------------------------------------------------------------------
module "cluster" {
  source              = "../../modules/cluster"
  name                = local.cluster.name
  location            = local.cluster.location
  resource_group_name = azurerm_resource_group.this.name
  subnet_id           = module.cluster_network.subnet_id
  public_ip_id        = module.cluster_network.public_ip_id
  public_ip_address   = module.cluster_network.public_ip_address
  vm_size             = local.cluster.vm_size
  os_disk_gb          = local.cluster.disk_gb
  ssh_public_key      = local.ssh_public_key
  k3s_version         = local.k3s_version
  registry_host       = "${module.tools_network.public_ip_address}:8082"
  namespaces          = local.namespaces
  tags                = local.tags
}

module "tools" {
  source              = "../../modules/registry"
  name                = local.tools.name
  location            = local.tools.location
  resource_group_name = azurerm_resource_group.this.name
  subnet_id           = module.tools_network.subnet_id
  public_ip_id        = module.tools_network.public_ip_id
  vm_size             = local.tools.vm_size
  os_disk_gb          = local.tools.disk_gb
  ssh_public_key      = local.ssh_public_key
  tags                = local.tags
}

# --- Monitoreo de Azure y costos -------------------------------------------------
module "observability" {
  source              = "../../modules/observability"
  name                = "circleguard"
  resource_group_name = azurerm_resource_group.this.name
  alert_emails        = var.alert_emails
  monthly_budget_usd  = var.monthly_budget_usd
  budget_start_date   = "2026-11-01T00:00:00Z"
  tags                = local.tags

  monitored_vm_ids = {
    (local.cluster.name) = module.cluster.vm_id
    (local.tools.name)   = module.tools.vm_id
  }
}
