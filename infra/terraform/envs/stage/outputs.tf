output "namespace" {
  description = "Namespace de Kubernetes del ambiente."
  value       = "circleguard-${local.env}"
}

output "key_vault_name" {
  description = "Key Vault con los secretos del ambiente (create-secrets.sh)."
  value       = module.secrets.name
}

output "backups_account" {
  description = "Storage Account de respaldos del ambiente."
  value       = module.backups.name
}

output "web_url" {
  description = "URL pública del web del ambiente."
  value       = data.terraform_remote_state.shared.outputs.web_urls[local.env]
}
