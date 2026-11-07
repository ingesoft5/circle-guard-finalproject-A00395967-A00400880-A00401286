# Pruebas E2E (Playwright)

Pruebas de extremo a extremo sobre el cliente web de CircleGuard. En el pipeline corren contra **dev** después de cada despliegue; si fallan, la versión **no** se promueve a stage.

## Ejecutar en local

```bash
cd tests/e2e-playwright
npm ci
npx playwright install chromium
BASE_URL=http://localhost:8080 npx playwright test
npx playwright show-report
```

`BASE_URL` es la URL del cliente web (por defecto `http://localhost:8080`).

## Estructura

| Ruta | Qué es |
|---|---|
| `playwright.config.ts` | Configuración: `BASE_URL`, reintentos en CI, trazas y capturas solo en fallos |
| `specs/smoke.spec.ts` | Prueba de humo: la pantalla de login carga |
| `specs/*.spec.ts` | Flujos E2E: inicio de sesión, cuestionario de salud y validación de acceso |
| `results/junit.xml` | Reporte JUnit (lo publica Jenkins) |
| `playwright-report/` | Reporte HTML (lo publica Jenkins como "Playwright") |

## Versión

La versión de `@playwright/test` está fijada en `package.json`. El pipeline usa la imagen `mcr.microsoft.com/playwright:v<versión>-jammy` y lee esa versión del `package.json`, así que para actualizar Playwright basta con cambiarla allí (y regenerar `package-lock.json` con `npm install`).
