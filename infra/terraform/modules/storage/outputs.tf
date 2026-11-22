output "id" {
  description = "ID de la Storage Account."
  value       = azurerm_storage_account.this.id
}

output "name" {
  description = "Nombre de la Storage Account."
  value       = azurerm_storage_account.this.name
}

output "blob_endpoint" {
  description = "Endpoint de blobs."
  value       = azurerm_storage_account.this.primary_blob_endpoint
}
