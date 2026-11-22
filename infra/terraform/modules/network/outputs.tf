output "subnet_id" {
  description = "ID de la subred."
  value       = azurerm_subnet.this.id
}

output "public_ip_id" {
  description = "ID de la IP pública estática."
  value       = azurerm_public_ip.this.id
}

output "public_ip_address" {
  description = "Dirección de la IP pública (se conoce antes de crear la VM)."
  value       = azurerm_public_ip.this.ip_address
}

output "fqdn" {
  description = "Nombre DNS de la IP pública, si se pidió dns_label."
  value       = azurerm_public_ip.this.fqdn
}
