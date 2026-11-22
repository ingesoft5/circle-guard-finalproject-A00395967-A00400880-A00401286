output "vm_id" {
  description = "ID de la VM del nodo."
  value       = azurerm_linux_virtual_machine.this.id
}

output "private_ip" {
  description = "IP privada del nodo."
  value       = azurerm_network_interface.this.private_ip_address
}

output "api_server" {
  description = "URL de la API de Kubernetes (la del kubeconfig de Jenkins)."
  value       = "https://${var.public_ip_address}:6443"
}

output "kubeconfig_command" {
  description = "Cómo bajar el kubeconfig de administrador (nunca al repo)."
  value       = "ssh ${var.admin_username}@${var.public_ip_address} sudo cat /etc/rancher/k3s/k3s.yaml | sed 's/127.0.0.1/${var.public_ip_address}/' > ~/.kube/circleguard.yaml"
}
