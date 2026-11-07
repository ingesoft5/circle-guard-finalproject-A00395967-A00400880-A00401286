# Pruebas E2E (CG-45, CG-46, CG-47)

Playwright 1.48.2 en `tests/e2e-playwright`. El pipeline las corre después del
despliegue a **dev** (`cgE2E`, imagen `mcr.microsoft.com/playwright:v1.48.2-jammy`)
y, si fallan, **stage no recibe la versión**. Jenkins publica el reporte JUnit y
el HTML de Playwright en cada build.

## Los 3 flujos

| Spec | Flujo | Qué recorre | Qué se verifica |
|---|---|---|---|
| `login.spec.ts` | Inicio de sesión | navegador → nginx → auth-service → identity-service → PostgreSQL | login válido (JWT + id anónimo, pantalla principal), credenciales inválidas (401 y alerta), cerrar sesión |
| `questionnaire.spec.ts` | Cuestionario de salud | UI → form-service → PostgreSQL (+ evento a Kafka) | la encuesta se guarda asociada al **id anónimo**, nunca al usuario real; la app vuelve al inicio |
| `campus-access.spec.ts` | Validación de acceso | auth-service emite el QR → gateway-service lo valida con Redis | QR vigente → `GREEN`; QR alterado o inválido → `RED` |

`smoke.spec.ts` (base de Simón, CG-44) es la primera señal de que el despliegue respondió.

## Decisión: cómo se prueba el acceso sin cámara

El escáner de la portería (`mobile/app/(tabs)/scan.tsx`) usa `expo-camera` y
re-autenticación biométrica. En un navegador headless no hay cámara ni biometría.

| Opción | Veredicto |
|---|---|
| Chromium con `--use-fake-device-for-media-stream` y un video `.y4m` del QR | Descartada: frágil (el QR rota cada 60 s, habría que generar el video en cada ejecución) y la biometría sigue bloqueando |
| Simular el gateway con mocks | Descartada: ya no sería E2E |
| **Frontera real** (elegida) | El usuario inicia sesión en la web; la pantalla principal pide su QR a auth-service y lo dibuja. El spec toma **ese mismo token** de la respuesta real y lo presenta a gateway-service (`POST /api/v1/gate/validate`), como haría el escáner |

Así se prueban de verdad los dos servicios y Redis. Lo único que no se ejerce
es la lectura óptica del QR, que es responsabilidad de la librería de cámara.

## Datos de prueba

- **Usuarios:** los sembrados por `auth-service` (`V2__seed_test_users.sql`,
  contraseña de `V3__fix_user_passwords.sql`). Se pueden cambiar con `E2E_USER` y
  `E2E_PASS` en el job de Jenkins.
- **Cuestionario:** form-service no siembra ninguno; el spec crea y activa uno
  propio por la API, con título único por ejecución.

## Hallazgo: el QR vence en 300 ms

`qr.expiration: 300` en auth-service y gateway-service son **milisegundos**. El
test "QR vigente permite el ingreso" **falla** hasta que se corrija a `300000`
(5 minutos), que es la corrección pendiente de Luis. Es justo lo que debe hacer
un E2E: detectó un defecto real que ninguna prueba unitaria veía.

## Correr en local

```bash
cd tests/e2e-playwright
npm ci
npx playwright install chromium
BASE_URL=http://<host-del-ambiente> npx playwright test
npx playwright show-report
```
