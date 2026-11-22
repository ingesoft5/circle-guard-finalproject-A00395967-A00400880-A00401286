output "id" {
  description = "ID del Key Vault."
  value       = azurerm_key_vault.this.id
}

output "name" {
  description = "Nombre del Key Vault (lo usa create-secrets.sh)."
  value       = azurerm_key_vault.this.name
}

output "secret_names" {
  description = "Nombres de los secretos (sin valores)."
  value       = keys(azurerm_key_vault_secret.this)
}
