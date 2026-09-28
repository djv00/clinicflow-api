import { test, expect, signIn, seedEncounter, openPatient } from './support.js';

test('viewer can inspect an encounter but cannot perform clinical writes', async ({ page, operatorApi }) => {
  const { patient, encounter } = await seedEncounter(operatorApi);
  await signIn(page, 'viewer');
  await expect(page.getByRole('button', { name: 'Register patient', exact: true })).toBeHidden();
  await openPatient(page, patient.medicalRecordNumber);
  const row = page.locator('#encounter-rows tr').filter({ hasText: encounter.encounterNumber });
  await expect(row).toContainText('In department');
  await expect(row.getByRole('button', { name: /^Transfer for / })).toBeHidden();
  await expect(row.getByRole('button', { name: /^Discharge for / })).toBeHidden();
  await row.getByRole('button', { name: /^Physician responsibility for / }).click();
  await expect(page.locator('#responsibility-status')).toHaveText('In department');
  await expect(page.getByRole('button', { name: 'Assign physician', exact: true })).toBeHidden();
  // Use this browser's cookie jar and a valid CSRF token to exercise authorization, not token rejection.
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const response = await page.request.post('/api/v1/patients', {
    headers: { [csrf.headerName]: csrf.token },
    data: { medicalRecordNumber: 'FORBIDDEN', firstName: 'Test', lastName: 'Viewer', dateOfBirth: '1990-01-01' }
  });
  expect(response.status()).toBe(403);
  expect((await response.json()).title).toBe('Access denied');
});

test('signing out invalidates another tab without retrying its registration', async ({ page, context }) => {
  await signIn(page);
  const second = await context.newPage();
  await second.goto('/');
  await second.getByRole('button', { name: 'Register patient', exact: true }).click();
  await second.getByLabel('Medical record number', { exact: true }).fill('SIGNED-OUT');
  await second.getByLabel('First name', { exact: true }).fill('Test');
  await second.getByLabel('Last name', { exact: true }).fill('Session');
  await second.getByLabel('Date of birth', { exact: true }).fill('1990-01-01');
  await page.getByRole('button', { name: 'Sign out', exact: true }).click();
  await expect(page).toHaveURL(/login\.html/);
  let writes = 0;
  second.on('request', request => { if (request.method() === 'POST' && request.url().endsWith('/api/v1/patients')) writes++; });
  await second.getByRole('button', { name: 'Save patient', exact: true }).click();
  await expect(second).toHaveURL(/login\.html/);
  expect(writes).toBe(1);
});

test('failed permission loading keeps writes hidden until a successful reload', async ({ page }) => {
  await signIn(page);
  await expect(page.getByRole('button', { name: 'Register patient', exact: true })).toBeVisible();
  await page.route('**/api/auth/session', route => route.abort('failed'));
  await page.reload();
  await expect(page.locator('#account-error')).toContainText('Could not load your account permissions');
  await expect(page.getByRole('button', { name: 'Register patient', exact: true })).toBeHidden();
  await page.unroute('**/api/auth/session');
  await page.reload();
  await expect(page.getByRole('button', { name: 'Register patient', exact: true })).toBeVisible();
});
