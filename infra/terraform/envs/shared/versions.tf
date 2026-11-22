terraform {
  # Misma versión que la imagen del pipeline (hashicorp/terraform:1.16.7).
  required_version = "~> 1.16.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.13"
    }
  }
}

provider "azurerm" {
  features {}
  storage_use_azuread = true
}
