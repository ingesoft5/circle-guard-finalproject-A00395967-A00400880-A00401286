import { expect, test, type Response } from '@playwright/test';
import { api, login } from './support/session';

// CG-47 · Flujo E2E 3: validación de acceso al campus.
//
// El escáner de la portería (scan.tsx) usa la cámara y re-autenticación
// biométrica, que no existen en un navegador headless. En lugar de simular la
// cámara (frágil), el spec prueba el flujo de punta a punta por la frontera
// real (decisión documentada en docs/pruebas/e2e.md):
//   1. El usuario inicia sesión en la web y la pantalla principal pide su QR
//      firmado a auth-service (/qr/generate), que lo dibuja en pantalla.
//   2. El spec toma ese mismo token de la respuesta real.
//   3. Lo presenta al gateway-service como lo haría el escáner
//      (POST /api/v1/gate/validate) y verifica la decisión de la portería.

const isQrCall = (r: Response) => r.url().includes('/api/v1/auth/qr/generate') && r.status() === 200;

test.describe('Validación de acceso al campus', () => {
  test('el QR vigente de un usuario sano permite el ingreso', async ({ page, request }) => {
    const qrResponse = page.waitForResponse(isQrCall);
    await login(page);
    const { qrToken } = await (await qrResponse).json();
    expect(qrToken, 'auth-service emite un token QR').toBeTruthy();

    // La pantalla principal dibuja el QR (react-native-qrcode-svg -> <svg>).
    // El primer <svg> de la página es el fondo decorativo (oculto): solo los visibles.
    await expect(page.locator('svg').locator('visible=true').first()).toBeVisible();

    const gate = await request.post(`${api.gate}/validate`, { data: { token: qrToken } });
    expect(gate.ok()).toBeTruthy();
    expect(await gate.json()).toMatchObject({ valid: true, status: 'GREEN' });
  });

  test('un QR alterado se rechaza', async ({ page, request }) => {
    const qrResponse = page.waitForResponse(isQrCall);
    await login(page);
    const { qrToken } = await (await qrResponse).json();

    // Cambiar la firma simula un QR falsificado o copiado y editado.
    const last = qrToken.at(-1) === 'A' ? 'B' : 'A';
    const forged = qrToken.slice(0, -1) + last;

    const gate = await request.post(`${api.gate}/validate`, { data: { token: forged } });
    expect(gate.ok()).toBeTruthy();
    expect(await gate.json()).toMatchObject({
      valid: false,
      status: 'RED',
      message: 'Invalid or Expired Token',
    });
  });

  test('un token que no es un QR firmado se rechaza', async ({ request }) => {
    const gate = await request.post(`${api.gate}/validate`, { data: { token: 'no-es-un-qr' } });
    expect(await gate.json()).toMatchObject({ valid: false, status: 'RED' });
  });
});
