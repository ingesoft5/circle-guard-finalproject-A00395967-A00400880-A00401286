import { expect, test } from '@playwright/test';

// Prueba de humo: el cliente web desplegado responde y muestra la pantalla de
// inicio de sesión. No depende de usuarios ni de datos, así que es la primera
// señal de que el despliegue quedó bien. Los tres flujos E2E de E1.6
// (inicio de sesión, cuestionario de salud y validación de acceso) van en
// archivos aparte dentro de specs/.
test.describe('Smoke: cliente web', () => {
  test('la pantalla de login carga', async ({ page }) => {
    const response = await page.goto('/login');

    expect(response?.ok(), `HTTP ${response?.status()} en /login`).toBeTruthy();
    await expect(page.getByText('Welcome Back')).toBeVisible();
    await expect(page.getByPlaceholder('Username')).toBeVisible();
    await expect(page.getByPlaceholder('Password')).toBeVisible();
    await expect(page.getByText('SIGN IN', { exact: true })).toBeVisible();
  });
});
