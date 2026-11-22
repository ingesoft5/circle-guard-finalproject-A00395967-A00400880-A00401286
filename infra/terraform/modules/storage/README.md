# Módulo `storage`

Storage Account para los respaldos de un ambiente, con contenedores privados.

- TLS 1.2, solo HTTPS, sin blobs públicos y **sin llaves compartidas**: se
  entra solo con Azure AD.
- Firewall: `Deny` por defecto; solo entran las IPs de `allowed_ips`
  (administrador y Jenkins).
- Versionado y borrado suave (`retention_days`), para recuperar un respaldo
  borrado o pisado.

El estado de Terraform **no** vive aquí: va en su propia cuenta, creada por
`infra/terraform/bootstrap/state.sh` antes del primer `init`.

Salidas: `id`, `name`, `blob_endpoint`.
