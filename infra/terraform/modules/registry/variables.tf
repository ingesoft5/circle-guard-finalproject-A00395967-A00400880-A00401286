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
