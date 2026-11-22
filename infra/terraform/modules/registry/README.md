# Módulo `registry`

VM **tools** con Jenkins, SonarQube y Nexus (registro Docker `circleguard`,
HTTP 8082, `ALLOW_ONCE`). Terraform declara la VM, el disco y la NIC. El
software lo instala y configura el bootstrap idempotente de la Parte 1, que ya
estaba probado:

```bash
./infra/azure/bootstrap-all.sh tools   # usa la IP pública cg-tools-ip de circleguard-rg
```

Así no se duplica en cloud-init lo que el script ya hace y documenta (usuarios
de Nexus, quality gate de Sonar, webhook y secretos generados en el servidor).

Salidas: `vm_id`, `private_ip`. La IP pública sale del módulo `network`.
