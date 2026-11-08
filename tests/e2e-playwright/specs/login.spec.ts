import { expect, test } from '@playwright/test';
import { E2E_USER, login, submitLogin } from './support/session';

// CG-45 · Flujo E2E 1: inicio de sesión.
//
// Recorre la frontera real: navegador -> nginx (cliente web) -> auth-service
// -> identity-service (id anónimo) -> PostgreSQL. Ningún backend está simulado.
test.describe('Inicio de sesión', () => {
  test('un usuario válido entra y ve su pase de acceso', async ({ page }) => {
    const session = await login(page);

    // Pantalla principal: estado y pase QR del usuario.
    await expect(page.getByText('STATUS').locator('visible=true').first()).toBeVisible();
    await expect(page.getByText('LOGOUT').locator('visible=true').first()).toBeVisible();

    // Privacidad: lo que identifica al usuario en la app es el id anónimo,
    // nunca el usuario real.
    expect(session.anonymousId).not.toContain(E2E_USER);
  });

  test('credenciales inválidas se rechazan y el usuario sigue en el login', async ({ page }) => {
    // En la web, la app muestra el error con window.alert.
    const dialog = page.waitForEvent('dialog');
    const response = await submitLogin(page, E2E_USER, 'contraseña-incorrecta');

    expect(response.status()).toBe(401);
    const alert = await dialog;
    expect(alert.message()).toContain('401');
    await alert.accept();
    await expect(page).toHaveURL(/\/login/);
  });

  test('cerrar sesión vuelve al login', async ({ page }) => {
    await login(page);
    await page.getByText('LOGOUT').locator('visible=true').first().click();
    await expect(page).toHaveURL(/\/login/);
    await expect(page.getByText('Welcome Back')).toBeVisible();
  });
});
