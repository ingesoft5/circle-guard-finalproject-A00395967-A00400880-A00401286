# Ambiente prod: solo llama a los módulos. dev, stage y prod tienen el mismo
# código; cambian el nombre y la retención (locals).
# La plataforma (clúster, tools) es compartida: está en envs/shared.

locals {
  env            = "prod"
  retention_days = 30
  tags           = { project = "circleguard", env = local.env, managed_by = "terraform" }
  # Sufijo estable por suscripción para los nombres globales únicos.
  suffix = substr(data.azurerm_client_config.current.subscription_id, 0, 8)
}

data "azurerm_client_config" "current" {}

data "terraform_remote_state" "shared" {
  backend = "azurerm"
  config = {
    resource_group_name  = "cg-tfstate-rg"
    storage_account_name = "cgtfstate6397764e"
    container_name       = "tfstate"
    key                  = "circleguard/shared.tfstate"
    use_azuread_auth     = true
  }
}

resource "azurerm_resource_group" "this" {
  name     = "cg-${local.env}-rg"
  location = "westus2"
  tags     = local.tags
}

module "secrets" {
  source              = "../../modules/secrets"
  name                = "cg-${local.env}-kv-${local.suffix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  allowed_ips         = data.terraform_remote_state.shared.outputs.allowed_ips
  tags                = local.tags

  # Object ids (no son secretos): administrador y service principal circleguard-jenkins.
  officer_principal_ids = {
    admin   = "7ba21a62-1d4b-471a-b4ff-39bc3f679845"
    jenkins = "53b41d1f-5c62-477b-916f-615d6a52c8c3"
  }

  # Los que deploy/docker-compose.yml lee de variables secretas.
  secret_names = [
    "postgres-password",
    "neo4j-password",
    "ldap-admin-password",
    "jwt-secret",
    "qr-secret",
    "vault-secret",
    "vault-salt",
  ]
}

module "backups" {
  source              = "../../modules/storage"
  name                = "cg${local.env}bk${local.suffix}"
  location            = azurerm_resource_group.this.location
  resource_group_name = azurerm_resource_group.this.name
  containers          = ["postgres", "neo4j"]
  retention_days      = local.retention_days
  allowed_ips         = data.terraform_remote_state.shared.outputs.allowed_ips
  tags                = local.tags
}
