# Estado remoto en Azure Storage (CG-79). Lo crea infra/terraform/bootstrap/state.sh.
# El bloqueo es un lease sobre el blob: dos plan/apply a la vez no pueden pisarse.
# Con estos valores `terraform init` funciona sin argumentos (local y Jenkins).
terraform {
  backend "azurerm" {
    resource_group_name  = "cg-tfstate-rg"
    storage_account_name = "cgtfstate6397764e"
    container_name       = "tfstate"
    key                  = "circleguard/shared.tfstate"
    use_azuread_auth     = true
  }
}
