variable "name" {
  description = "Prefijo de los recursos de monitoreo."
  type        = string
}

variable "resource_group_name" {
  description = "Grupo de recursos donde viven las alertas."
  type        = string
}

variable "alert_emails" {
  description = "Correos que reciben las alertas de presupuesto y de VM caída (fuera del repo, en terraform.tfvars)."
  type        = list(string)
}

variable "monthly_budget_usd" {
  description = "Presupuesto mensual de la suscripción en USD."
  type        = number
}

variable "budget_start_date" {
  description = "Primer día del mes en que empieza el presupuesto (YYYY-MM-01T00:00:00Z)."
  type        = string
}

variable "monitored_vm_ids" {
  description = "VMs (nombre => id) con alerta si dejan de estar disponibles."
  type        = map(string)
}

variable "tags" {
  description = "Tags comunes (project, env, ...)."
  type        = map(string)
}
