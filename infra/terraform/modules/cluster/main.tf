# Clúster k3s de un nodo (server + agent). Decisión y tamaño en el README.

resource "azurerm_network_interface" "this" {
  name                = "${var.name}-nic"
  location            = var.location
  resource_group_name = var.resource_group_name
  tags                = var.tags

  ip_configuration {
    name                          = "primary"
    subnet_id                     = var.subnet_id
    private_ip_address_allocation = "Dynamic"
    public_ip_address_id          = var.public_ip_id
  }
}

resource "azurerm_linux_virtual_machine" "this" {
  name                            = var.name
  location                        = var.location
  resource_group_name             = var.resource_group_name
  size                            = var.vm_size
  admin_username                  = var.admin_username
  disable_password_authentication = true
  network_interface_ids           = [azurerm_network_interface.this.id]
  tags                            = merge(var.tags, { role = "k3s" })

  custom_data = base64encode(templatefile("${path.module}/cloud-init.yaml.tftpl", {
    public_ip     = var.public_ip_address
    k3s_version   = var.k3s_version
    registry_host = var.registry_host
    namespaces    = var.namespaces
  }))

  admin_ssh_key {
    username   = var.admin_username
    public_key = var.ssh_public_key
  }

  os_disk {
    caching              = "ReadWrite"
    storage_account_type = "StandardSSD_LRS"
    disk_size_gb         = var.os_disk_gb
  }

  source_image_reference {
    publisher = "Canonical"
    offer     = "0001-com-ubuntu-server-jammy"
    sku       = "22_04-lts-gen2"
    version   = "latest"
  }

  lifecycle {
    # cloud-init solo corre en el primer arranque. Cambiarlo obligaría a
    # recrear la VM (y perder los PVC): los cambios posteriores del clúster se
    # hacen con kubectl/Helm, no reemplazando el nodo.
    ignore_changes = [custom_data, source_image_reference]
  }
}
