import { defineConfig, devices } from '@playwright/test';

// Pruebas E2E del cliente web de CircleGuard.
//
// BASE_URL apunta al ambiente que se prueba. En el pipeline es dev
// (http://<CG_DEV_HOST>:<CG_WEB_PORT>); en local, el cliente web en 8080.
//
// Reportes (los publica Jenkins):
//   - results/junit.xml   -> pestaña de pruebas del build
//   - playwright-report/  -> reporte HTML "Playwright" del build
//
// La versión de @playwright/test (package.json) debe coincidir con la imagen
// mcr.microsoft.com/playwright:vX.Y.Z-jammy que usa el pipeline; el pipeline
// la lee del package.json.
export default defineConfig({
  testDir: './specs',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    ['junit', { outputFile: 'results/junit.xml' }],
  ],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:8080',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
});
