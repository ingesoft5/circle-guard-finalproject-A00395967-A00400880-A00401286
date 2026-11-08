import { expect, test } from '@playwright/test';
import { api, login } from './support/session';

// CG-46 · Flujo E2E 2: cuestionario de salud.
//
// Es la entrada del flujo de negocio central: el estudiante responde el
// cuestionario en la web -> form-service guarda la encuesta y publica el evento
// en Kafka -> promotion-service lo consume. El spec recorre la UI real y
// verifica que form-service aceptó y guardó la encuesta del usuario anónimo.
//
// Datos: form-service no siembra cuestionarios, así que el spec crea y activa
// uno propio por la API antes de empezar (título único por ejecución).

test.describe('Cuestionario de salud', () => {
  test.describe.configure({ mode: 'serial' });

  const title = `E2E Health Screening ${Date.now()}`;

  test.beforeAll(async ({ request }) => {
    const created = await request.post(`${api.form}/questionnaires`, {
      data: {
        title,
        description: 'Cuestionario creado por la prueba E2E',
        version: 1,
        isActive: false,
        questions: [
          { text: '¿Tiene fiebre?', type: 'YES_NO', orderIndex: 0 },
          {
            text: '¿Cómo se siente hoy?',
            type: 'SINGLE_CHOICE',
            options: JSON.stringify(['Bien', 'Regular', 'Mal']),
            orderIndex: 1,
          },
        ],
      },
    });
    expect(created.status(), 'crear cuestionario').toBe(200);
    const { id } = await created.json();

    const activated = await request.post(`${api.form}/questionnaires/${id}/activate`);
    expect(activated.ok(), 'activar cuestionario').toBeTruthy();
  });

  test('el estudiante responde y envía el cuestionario', async ({ page }) => {
    const session = await login(page);

    await page.goto('/questionnaire');
    await expect(page.getByText('Health Screening')).toBeVisible();
    await expect(page.getByText('¿Tiene fiebre?')).toBeVisible();

    await page.getByText('NO', { exact: true }).click();
    await page.getByText('Bien', { exact: true }).click();

    const [submitted] = await Promise.all([
      page.waitForResponse(
        (r) => r.url().includes('/api/v1/surveys') && r.request().method() === 'POST',
      ),
      page.getByText('SUBMIT REPORT').click(),
    ]);

    expect(submitted.status(), 'form-service acepta la encuesta').toBe(200);
    const survey = await submitted.json();
    expect(survey.id, 'la encuesta quedó persistida').toBeTruthy();
    // La encuesta se asocia al id anónimo, nunca al usuario real.
    expect(survey.anonymousId).toBe(session.anonymousId);

    // La app vuelve a la pantalla principal.
    await expect(page).not.toHaveURL(/questionnaire/);
    await expect(page.getByText('STATUS')).toBeVisible();
  });

  test('el cuestionario activo es el que se respondió', async ({ request }) => {
    const active = await request.get(`${api.form}/questionnaires/active`);
    expect(active.ok()).toBeTruthy();
    expect((await active.json()).title).toBe(title);
  });
});
