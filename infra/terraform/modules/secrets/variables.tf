variable "name" {
  description = "Nombre global único del Key Vault (3-24 caracteres)."
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

variable "secret_names" {
  description = "Secretos que se generan al azar (nombre en Key Vault, con guiones)."
  type        = list(string)
}

variable "secret_bytes" {
  description = "Bytes aleatorios por secreto (32 = 64 caracteres hex)."
  type        = number
  default     = 32
}

variable "soft_delete_days" {
  description = "Días que un secreto borrado se puede recuperar (7-90)."
  type        = number
  default     = 7
}

variable "allowed_ips" {
  description = "IPs públicas que pueden leer los secretos (administrador y Jenkins)."
  type        = list(string)
}

variable "tags" {
  description = "Tags comunes (project, env, ...)."
  type        = map(string)
}

variable "officer_principal_ids" {
  description = "Principales (nombre => object id) con rol Key Vault Secrets Officer. Debe incluir a quien ejecuta Terraform."
  type        = map(string)
}
