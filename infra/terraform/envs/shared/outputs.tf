# Los ambientes leen estas salidas con terraform_remote_state.

output "cluster_public_ip" {
  description = "IP pública del nodo k3s (Ingress y API)."
  value       = module.cluster_network.public_ip_address
}

output "cluster_api_server" {
  description = "API de Kubernetes para el kubeconfig de Jenkins."
  value       = module.cluster.api_server
}

output "kubeconfig_command" {
  description = "Cómo bajar el kubeconfig de administrador."
  value       = module.cluster.kubeconfig_command
}

output "tools_public_ip" {
  description = "IP pública de tools (Jenkins :8080, Sonar :9000, Nexus :8081)."
  value       = module.tools_network.public_ip_address
}

output "registry" {
  description = "Registro Docker para CG_REGISTRY y global.registry."
  value       = "${module.tools_network.public_ip_address}:8082"
}

output "web_urls" {
  description = "URL pública del web por ambiente (CG_DEV_URL, CG_STAGE_URL, CG_PROD_URL)."
  value = {
    for env in ["dev", "stage", "prod"] :
    env => "https://${env}.${replace(module.cluster_network.public_ip_address, ".", "-")}.sslip.io"
  }
}

output "allowed_ips" {
  description = "IPs sin máscara con acceso al plano de datos de Key Vault y Storage (administrador y Jenkins)."
  value       = [split("/", var.admin_cidr)[0], module.tools_network.public_ip_address]
}
