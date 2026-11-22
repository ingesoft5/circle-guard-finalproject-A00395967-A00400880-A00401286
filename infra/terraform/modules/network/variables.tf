variable "name" {
  description = "Prefijo de los recursos (p. ej. cg-k3s)."
  type        = string
}

variable "location" {
  description = "Región de Azure."
  type        = string
}

variable "resource_group_name" {
  description = "Grupo de recursos donde se crea la red."
  type        = string
}

variable "address_space" {
  description = "Rango de la VNet."
  type        = string
}

variable "subnet_prefix" {
  description = "Rango de la subred (dentro de address_space)."
  type        = string
}

variable "inbound_rules" {
  description = "Reglas de entrada del NSG. Todo lo que no esté aquí queda bloqueado desde Internet."
  type = list(object({
    name     = string
    priority = number
    ports    = list(string)
    sources  = list(string)
  }))
}

variable "dns_label" {
  description = "Etiqueta DNS de la IP pública (<label>.<región>.cloudapp.azure.com). null = sin DNS."
  type        = string
  default     = null
}

variable "tags" {
  description = "Tags comunes (project, env, ...)."
  type        = map(string)
}
