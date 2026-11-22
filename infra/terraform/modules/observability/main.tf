# Observabilidad del lado de Azure. Prometheus, Grafana, Loki y Jaeger viven
# DENTRO del clúster (namespace observability) y los instala Simón con
# observability/install.sh. Aquí va lo que el clúster no puede ver de sí mismo:
# que la VM se caiga y cuánto se está gastando (la cuenta de la Parte 1 se
# apagó por quedarse sin crédito).

data "azurerm_subscription" "current" {}

resource "azurerm_monitor_action_group" "this" {
  name                = "${var.name}-ag"
  resource_group_name = var.resource_group_name
  short_name          = "cgalerts"
  tags                = var.tags

  dynamic "email_receiver" {
    for_each = var.alert_emails
    content {
      name                    = "email-${email_receiver.key}"
      email_address           = email_receiver.value
      use_common_alert_schema = true
    }
  }
}

# Avisos al 50 %, 80 % y 100 % del presupuesto, y cuando el pronóstico lo supera.
resource "azurerm_consumption_budget_subscription" "this" {
  name            = "${var.name}-budget"
  subscription_id = data.azurerm_subscription.current.id
  amount          = var.monthly_budget_usd
  time_grain      = "Monthly"

  time_period {
    start_date = var.budget_start_date
  }

  dynamic "notification" {
    for_each = [50, 80, 100]
    content {
      enabled        = true
      threshold      = notification.value
      operator       = "GreaterThanOrEqualTo"
      threshold_type = "Actual"
      contact_groups = [azurerm_monitor_action_group.this.id]
    }
  }

  notification {
    enabled        = true
    threshold      = 100
    operator       = "GreaterThanOrEqualTo"
    threshold_type = "Forecasted"
    contact_groups = [azurerm_monitor_action_group.this.id]
  }

  lifecycle {
    ignore_changes = [time_period]
  }
}

# VM caída: la métrica de plataforma VmAvailabilityMetric baja de 1.
resource "azurerm_monitor_metric_alert" "vm_down" {
  for_each = var.monitored_vm_ids

  name                = "${each.key}-down"
  resource_group_name = var.resource_group_name
  scopes              = [each.value]
  description         = "La VM ${each.key} no está disponible."
  severity            = 0
  frequency           = "PT1M"
  window_size         = "PT5M"
  tags                = var.tags

  criteria {
    metric_namespace = "Microsoft.Compute/virtualMachines"
    metric_name      = "VmAvailabilityMetric"
    aggregation      = "Average"
    operator         = "LessThan"
    threshold        = 1
  }

  action {
    action_group_id = azurerm_monitor_action_group.this.id
  }
}
