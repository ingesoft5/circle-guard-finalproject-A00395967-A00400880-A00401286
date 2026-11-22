output "vm_id" {
  description = "ID de la VM tools."
  value       = azurerm_linux_virtual_machine.this.id
}

output "private_ip" {
  description = "IP privada de tools."
  value       = azurerm_network_interface.this.private_ip_address
}
