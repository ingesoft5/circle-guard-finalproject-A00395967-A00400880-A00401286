output "action_group_id" {
  description = "Grupo de acción de las alertas de Azure."
  value       = azurerm_monitor_action_group.this.id
}

output "budget_id" {
  description = "Presupuesto mensual de la suscripción."
  value       = azurerm_consumption_budget_subscription.this.id
}
