# Mapa de servicios CircleGuard

| Servicio | Puerto | Base de datos | Dependencias | Notas |
|---|---|---|---|---|
| auth-service | 8180 | _verificar_ | _verificar_ | |
| identity-service | 8083 | _verificar_ | _verificar_ | |
| promotion-service | _verificar_ | Neo4j | Kafka (_verificar_) | Máquina de estados: Sospechoso → Probable → Confirmado |
| notification-service | 8082 | _verificar_ | _verificar_ | |
| form-service | 8086 | _verificar_ | Kafka | Publica eventos de formularios |
| file-service | 8085 | _verificar_ | _verificar_ | |
| gateway-service | 8087 | _verificar_ | Redis | Valida QR firmados |
| dashboard-service | 8084 | _verificar_ | _verificar_ | Aplica k-anonymity |

## Middleware

| Componente | Puerto por defecto | Usado por |
|---|---|---|
| PostgreSQL | 5432 | _verificar_ |
| Neo4j | 7687 (Bolt) / 7474 (HTTP) | promotion-service |
| Kafka | 9092 | form-service, promotion-service (_verificar_) |
| Redis | 6379 | gateway-service |
| OpenLDAP | 389 | auth-service / identity-service (_verificar_) |

## Variables de entorno

_Completar con las variables de cada `application.yml` y del `docker-compose`, sin valores de secretos._
