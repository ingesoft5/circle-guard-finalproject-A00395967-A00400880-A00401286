import { expect, type Page, type Response } from '@playwright/test';

// Utilidades compartidas por los flujos E2E (CG-45, CG-46, CG-47).
//
// Credenciales: se leen de E2E_USER / E2E_PASS (variables del job de Jenkins).
// Si no vienen, se usan los usuarios SEMBRADOS para pruebas por la migración
// auth-service V2__seed_test_users.sql + V3__fix_user_passwords.sql. Son datos
// de prueba públicos del repositorio, no credenciales reales.
export const E2E_USER = process.env.E2E_USER ?? 'health_user';
export const E2E_PASS = process.env.E2E_PASS ?? 'password';

// El cliente web es el único origen: nginx reenvía /svc/<servicio>/ a cada
// backend (mobile/nginx/default.conf), así que las llamadas directas a la API
// que hace un spec van por la misma URL base que usa el navegador.
export const api = {
  auth: '/svc/auth/api/v1/auth',
  form: '/svc/form/api/v1',
  gate: '/svc/gateway/api/v1/gate',
};

export interface Session {
  token: string;
  anonymousId: string;
}

const isLoginCall = (r: Response) => r.url().includes('/api/v1/auth/login') && r.request().method() === 'POST';

/** Llena el formulario de login con la UI real y espera la respuesta del backend. */
export async function submitLogin(page: Page, username: string, password: string): Promise<Response> {
  await page.goto('/login');
  await expect(page.getByText('Welcome Back')).toBeVisible();
  await page.getByPlaceholder('Username').fill(username);
  await page.getByPlaceholder('Password').fill(password);
  const [response] = await Promise.all([
    page.waitForResponse(isLoginCall),
    page.getByText('SIGN IN').click(),
  ]);
  return response;
}

/** Login válido por la UI. Devuelve el JWT y el identificador anónimo que emitió auth-service. */
export async function login(page: Page): Promise<Session> {
  const response = await submitLogin(page, E2E_USER, E2E_PASS);
  expect(response.status(), `login de ${E2E_USER}`).toBe(200);
  const body = await response.json();
  expect(body.token, 'auth-service debe emitir un JWT').toBeTruthy();
  expect(body.anonymousId, 'auth-service debe devolver el id anónimo de identity-service').toMatch(
    /^[0-9a-f-]{36}$/i,
  );
  // La app navega a las pestañas principales (ruta "/").
  await expect(page).not.toHaveURL(/\/login/);
  return { token: body.token, anonymousId: body.anonymousId };
}
