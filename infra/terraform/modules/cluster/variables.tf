variable "name" {
  description = "Nombre de la VM."
  type        = string
}

variable "location" {
  description = "Región de Azure (la misma de su red)."
  type        = string
}

variable "resource_group_name" {
  description = "Grupo de recursos."
  type        = string
}

variable "subnet_id" {
  description = "Subred del módulo network."
  type        = string
}

variable "public_ip_id" {
  description = "IP pública del módulo network."
  type        = string
}

variable "vm_size" {
  description = "Tamaño de la VM. Debe tener capacidad en la región (ver infra/azure/probe-capacity.sh)."
  type        = string
}

variable "os_disk_gb" {
  description = "Tamaño del disco del sistema en GB."
  type        = number
}

variable "admin_username" {
  description = "Usuario administrador (solo llave SSH, sin contraseña)."
  type        = string
  default     = "azureuser"
}

variable "ssh_public_key" {
  description = "Contenido de la llave pública SSH del administrador."
  type        = string
}

variable "tags" {
  description = "Tags comunes (project, env, ...)."
  type        = map(string)
}

variable "public_ip_address" {
  description = "IP pública del nodo: va como SAN del certificado de la API (Jenkins entra por ella)."
  type        = string
}

variable "k3s_version" {
  description = "Versión fija de k3s (nunca 'latest')."
  type        = string
}

variable "registry_host" {
  description = "host:puerto del registro Docker de Nexus (HTTP)."
  type        = string
}

variable "namespaces" {
  description = "Namespaces que se crean al arrancar el clúster, con su etiqueta de ambiente."
  type        = map(string)
}
