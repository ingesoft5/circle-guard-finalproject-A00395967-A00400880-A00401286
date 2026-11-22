terraform {
  # Misma versión que la imagen del pipeline (hashicorp/terraform:1.16.7).
  required_version = "~> 1.16.0"

  required_providers {
    azurerm = {
      source  = "hashicorp/azurerm"
      version = "~> 5.13"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.7"
    }
    time = {
      source  = "hashicorp/time"
      version = "~> 0.13"
    }
  }
}

provider "azurerm" {
  features {
    key_vault {
      # Al destruir el ambiente se purga el vault para poder recrearlo con el mismo nombre.
      purge_soft_delete_on_destroy = true
    }
  }
  # Sin llaves compartidas en las Storage Accounts: todo con Azure AD.
  storage_use_azuread = true
}
