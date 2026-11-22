variable "name" {
  description = "Nombre global único de la Storage Account (3-24 minúsculas y números)."
  type        = string
}

variable "location" {
  description = "Región de Azure."
  type        = string
}

variable "resource_group_name" {
  description = "Grupo de recursos."
  type        = string
}

variable "containers" {
  description = "Contenedores privados que se crean en la cuenta."
  type        = list(string)
}

variable "retention_days" {
  description = "Días de borrado suave de blobs y contenedores."
  type        = number
}

variable "replication" {
  description = "Tipo de réplica (LRS, ZRS, GRS)."
  type        = string
  default     = "LRS"
}

variable "allowed_ips" {
  description = "IPs públicas que pueden entrar al plano de datos (administrador y Jenkins). El resto se rechaza."
  type        = list(string)
}

variable "tags" {
  description = "Tags comunes (project, env, ...)."
  type        = map(string)
}
