# Patrón Feature Toggle (CG-101)

> Nota: `docs/patrones.md` (CG-100) aún no existe en esta rama. Cuando se cree, esta sección se traslada allí como "Feature Toggle".

## Qué es

Un *feature toggle* es una propiedad de configuración que enciende o apaga un comportamiento **sin recompilar** la imagen. Cambiar el valor y reiniciar el pod basta.

## Toggle implementado: modo estricto del torniquete

| Dato | Valor |
|---|---|
| Servicio | `circleguard-gateway-service` |
| Clase | `services/circleguard-gateway-service/src/main/java/com/circleguard/gateway/service/QrValidationService.java` |
| Propiedad | `circleguard.features.strict-gate.enabled` |
| Valor por defecto | `false` (comportamiento original; está en `application.yml` y como valor por defecto del `@Value`) |
| Variable de entorno | `CIRCLEGUARD_FEATURES_STRICT_GATE_ENABLED` |
| Pruebas | `QrValidationServiceTest` (3 casos, ver abajo) |

### Comportamiento

- **Apagado (por defecto):** si el usuario no tiene estado en Redis (`user:status:<id>` es `null`), el torniquete lo deja entrar (`GREEN`, "Welcome to Campus").
- **Encendido:** si no hay estado en Redis, se niega el ingreso con `RED` y el mensaje `Access Denied: Status unavailable`. Un usuario con estado `ACTIVE` sigue entrando.

### Cómo cambiarlo (relaxed binding de Spring)

```bash
# Docker / Kubernetes (ConfigMap o env del contenedor)
CIRCLEGUARD_FEATURES_STRICT_GATE_ENABLED=true
```

Spring convierte `CIRCLEGUARD_FEATURES_STRICT_GATE_ENABLED` en `circleguard.features.strict-gate.enabled`. En Kubernetes, Darío la agrega al ConfigMap (CG-82); el cambio requiere reiniciar el pod del gateway.

### Pruebas

En `QrValidationServiceTest`, con `ReflectionTestUtils.setField(service, "strictGateEnabled", ...)`:

1. Apagado + sin estado → permitido.
2. Encendido + sin estado → negado con `Access Denied: Status unavailable`.
3. Encendido + `ACTIVE` → permitido.

## Trade-offs

- **Deuda de toggles viejos:** un toggle que ya se decidió debe eliminarse (el código y la propiedad). Si no, quedan ramas muertas. Este toggle debe revisarse al cerrar el sprint.
- **Combinaciones sin probar:** cada toggle duplica los caminos posibles. Solo se prueban los casos listados arriba; otras combinaciones con estados como `SUSPECT` en modo estricto no tienen prueba propia (pendiente de evidencia).
- **Requiere reinicio:** el valor se lee una vez con `@Value`; no es un cambio en caliente.
- **Riesgo operativo del modo estricto:** si Redis pierde las claves (expiración o reinicio), con el toggle encendido se negaría el ingreso a todos. Por eso el valor por defecto es `false`.
