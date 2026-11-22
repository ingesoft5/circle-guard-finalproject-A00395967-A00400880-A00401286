# Red de un host: VNet + subred + NSG de mínimo privilegio + IP pública estática.
# El NSG va en la subred, así que protege a cualquier VM que se agregue después.

resource "azurerm_virtual_network" "this" {
  name                = "${var.name}-vnet"
  location            = var.location
  resource_group_name = var.resource_group_name
  address_space       = [var.address_space]
  tags                = var.tags
}

resource "azurerm_subnet" "this" {
  name                 = "default"
  resource_group_name  = var.resource_group_name
  virtual_network_name = azurerm_virtual_network.this.name
  address_prefixes     = [var.subnet_prefix]
}

resource "azurerm_network_security_group" "this" {
  name                = "${var.name}-nsg"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags
}

# Las reglas públicas (web 80/443, webhook de Jenkins) son intencionales y van
# en inbound_rules con origen "Internet"; el resto se restringe por IP.
#trivy:ignore:AVD-AZU-0047 Entrada pública solo en los puertos declarados (web y webhook); documentado en el README.
resource "azurerm_network_security_rule" "inbound" {
  for_each = { for r in var.inbound_rules : r.name => r }

  name                        = each.value.name
  priority                    = each.value.priority
  direction                   = "Inbound"
  access                      = "Allow"
  protocol                    = "Tcp"
  source_port_range           = "*"
  destination_port_ranges     = each.value.ports
  source_address_prefixes     = length(each.value.sources) > 1 ? each.value.sources : null
  source_address_prefix       = length(each.value.sources) == 1 ? each.value.sources[0] : null
  destination_address_prefix  = "*"
  resource_group_name         = var.resource_group_name
  network_security_group_name = azurerm_network_security_group.this.name
}

resource "azurerm_subnet_network_security_group_association" "this" {
  subnet_id                 = azurerm_subnet.this.id
  network_security_group_id = azurerm_network_security_group.this.id
}

resource "azurerm_public_ip" "this" {
  name                = "${var.name}-ip"
  location            = var.location
  resource_group_name = var.resource_group_name
  allocation_method   = "Static"
  sku                 = "Standard"
  domain_name_label   = var.dns_label
  tags                = var.tags
}
