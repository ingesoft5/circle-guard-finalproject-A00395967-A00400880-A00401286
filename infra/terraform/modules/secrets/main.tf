# Key Vault del ambiente con los secretos de la app generados al azar.
# De aquí los toma deploy/helm/circleguard/create-secrets.sh para crear el
# Secret de Kubernetes: ningún secreto pasa por el repo ni por un archivo.

data "azurerm_client_config" "current" {}

resource "azurerm_key_vault" "this" {
  name                       = var.name
  location                   = var.location
  resource_group_name        = var.resource_group_name
  tenant_id                  = data.azurerm_client_config.current.tenant_id
  sku_name                   = "standard"
  rbac_authorization_enabled = true
  soft_delete_retention_days = var.soft_delete_days
  # Sin purge protection para poder hacer `terraform destroy` después de la
  # sustentación sin esperar la retención. En un ambiente real: true.
  purge_protection_enabled = false #trivy:ignore:AVD-AZU-0016 Se destruye al cerrar el curso; documentado.
  tags                     = var.tags

  network_acls {
    default_action = "Deny"
    bypass         = "AzureServices"
    ip_rules       = var.allowed_ips
  }
}

# Lista explícita de quién administra los secretos (administrador y service
# principal de Jenkins). No se usa "quien ejecute Terraform": cada apply de
# Jenkins le quitaría el permiso al administrador, y al revés.
resource "azurerm_role_assignment" "officer" {
  for_each = var.officer_principal_ids

  scope                = azurerm_key_vault.this.id
  role_definition_name = "Key Vault Secrets Officer"
  principal_id         = each.value
}

moved {
  from = azurerm_role_assignment.officer
  to   = azurerm_role_assignment.officer["admin"]
}

# El permiso RBAC tarda en propagarse; sin esta espera el primer apply falla con 403.
resource "time_sleep" "rbac" {
  create_duration = "60s"
  depends_on      = [azurerm_role_assignment.officer]
}

# Hexadecimal, igual que `openssl rand -hex 32` en la Parte 1: VAULT_SALT
# tiene que ser hex y los HMAC necesitan al menos 32 caracteres.
resource "random_id" "this" {
  for_each = toset(var.secret_names)

  byte_length = var.secret_bytes
}

# El valor queda también en el estado de Terraform, que está cifrado y solo se
# lee con Azure AD (ver infra/terraform/README.md).
resource "azurerm_key_vault_secret" "this" {
  for_each = toset(var.secret_names)

  name         = each.value
  value        = random_id.this[each.value].hex
  key_vault_id = azurerm_key_vault.this.id
  content_type = "text/plain"

  depends_on = [time_sleep.rbac]
}
