variable "admin_cidr" {
  description = "IP pública del equipo en CIDR (/32). SSH, Sonar, Nexus y la API de k3s solo aceptan este origen y tools."
  type        = string

  validation {
    condition     = can(cidrhost(var.admin_cidr, 0))
    error_message = "admin_cidr debe ser un CIDR, p. ej. 203.0.113.10/32."
  }
}

variable "ssh_public_key_path" {
  description = "Llave pública SSH del administrador."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "alert_emails" {
  description = "Correos para las alertas de presupuesto y de VM caída."
  type        = list(string)
}

variable "monthly_budget_usd" {
  description = "Presupuesto mensual (la cuenta gratuita trae 200 USD de crédito)."
  type        = number
  default     = 150
}
