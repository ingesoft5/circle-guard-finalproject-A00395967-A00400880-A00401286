# Módulo `secrets`

Key Vault del ambiente, con RBAC, firewall (`Deny` salvo `allowed_ips`) y los
secretos de la app **generados al azar** por Terraform: `postgres-password`,
`jwt-secret`, etc. Nadie los escribe a mano y no pasan por el repo.

Flujo hasta Kubernetes:

```
Terraform ──random_id──▶ Key Vault cg-<env>-kv-… ──create-secrets.sh──▶ Secret circleguard-secrets (namespace)
```

- `officer_principal_ids` (administrador y service principal de Jenkins)
  reciben el rol *Key Vault Secrets Officer* sobre el vault, con 60 s de
  espera por la propagación de RBAC. La lista es explícita: si fuera "quien
  ejecute Terraform", cada apply le quitaría el permiso al otro.
- El valor también queda en el estado, que se guarda cifrado y solo se lee con
  Azure AD.
- `purge_protection_enabled = false` permite el `terraform destroy` al cerrar
  el curso. En producción real va en `true`.

Salidas: `id`, `name`, `secret_names`.
