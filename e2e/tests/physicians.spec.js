import { test, expect, signIn, uniqueCode, seedPhysician } from './support.js';

test('administrator maintains multiple affiliations and availability without clinical access', async ({ page, adminApi }) => {
  const code = uniqueCode('DR');
  await signIn(page, 'admin');
  await expect(page).toHaveURL(/physicians\.html/);
  await expect(page.locator('#patients-link')).toBeHidden();
  await expect(page.locator('#inpatients-link')).toBeHidden();
  expect((await page.request.get('/api/v1/patients')).status()).toBe(403);
  await page.getByRole('button', { name: 'Add physician', exact: true }).click();
  await page.getByLabel('Physician code', { exact: true }).fill(code);
  await page.getByLabel('First name', { exact: true }).fill('Test');
  await page.getByLabel('Last name', { exact: true }).fill('Directory');
  await page.getByRole('checkbox', { name: /Demo General Medicine/ }).check();
  await page.getByRole('checkbox', { name: /Demo Rehabilitation/ }).check();
  await page.locator('#save-physician').click();
  await expect(page.locator('#physician-saved')).toHaveText('Physician added.');
  await expect(page.getByLabel('Physician code', { exact: true })).not.toBeEditable();
  await page.getByRole('checkbox', { name: /Demo Rehabilitation/ }).uncheck();
  await page.getByLabel('Last name', { exact: true }).fill('Updated');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.locator('#physician-saved')).toHaveText('Changes saved.');
  await page.locator('#change-physician-active').click();
  await page.getByRole('button', { name: 'Confirm deactivation', exact: true }).click();
  await expect(page.locator('#physician-current-active')).toHaveText('Inactive');
  const result = await adminApi.get(`/api/v1/physicians?keyword=${code}`);
  expect(result.items).toHaveLength(1);
  expect(result.items[0].lastName).toBe('Updated');
  expect(result.items[0].active).toBe(false);
  expect(result.items[0].departments.map(item => item.departmentCode)).toEqual(['DEMO-MED']);
  expect(await adminApi.get('/api/v1/departments?active=true')).toHaveLength(2);
});

test('stale physician edits cannot overwrite a newer profile or affiliations', async ({ page, adminApi }) => {
  const departments = await adminApi.get('/api/v1/departments?active=true');
  const physician = await seedPhysician(adminApi, [departments[0].id]);
  await signIn(page, 'admin');
  await page.getByLabel('Search physicians', { exact: true }).fill(physician.physicianCode);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await page.getByRole('button', { name: new RegExp(`^Edit physician: .*${physician.physicianCode}$`) }).click();
  await page.getByLabel('Last name', { exact: true }).fill('Stale');
  await adminApi.write(`/api/v1/physicians/${physician.id}`, {
    firstName: physician.firstName, lastName: 'Newer', departmentIds: [departments[1].id], version: physician.version
  }, 'PUT', 200);
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect(page.locator('#physician-error')).toContainText('changed by another request');
  await expect(page.getByRole('button', { name: 'Save changes', exact: true })).toBeDisabled();
  await page.locator('#reload-physician').click();
  await expect(page.getByLabel('Last name', { exact: true })).toHaveValue('Newer');
  await expect(page.getByRole('checkbox', { name: new RegExp(departments[1].departmentName) })).toBeChecked();
  await expect(page.getByRole('checkbox', { name: new RegExp(departments[0].departmentName) })).not.toBeChecked();
  const saved = await adminApi.get(`/api/v1/physicians/${physician.id}`);
  expect(saved.lastName).toBe('Newer');
  expect(saved.departments.map(item => item.id)).toEqual([departments[1].id]);
});

test('unconfirmed physician creation finds the committed record before allowing another save', async ({ page, adminApi }) => {
  const code = uniqueCode('DR');
  await signIn(page, 'admin');
  await page.getByRole('button', { name: 'Add physician', exact: true }).click();
  await page.getByLabel('Physician code', { exact: true }).fill(code);
  await page.getByLabel('First name', { exact: true }).fill('Test');
  await page.getByLabel('Last name', { exact: true }).fill('Recovery');
  let writes = 0;
  await page.route('**/api/v1/physicians', async route => {
    if (route.request().method() !== 'POST') return route.continue();
    writes++;
    const saved = await route.fetch();
    expect(saved.status()).toBe(201);
    await route.abort('failed');
  });
  await page.locator('#save-physician').click();
  await expect(page.locator('#physician-error')).toContainText('Creation could not be confirmed');
  await expect(page.locator('#save-physician')).toBeDisabled();
  await page.getByRole('button', { name: 'Check physician code', exact: true }).click();
  await expect(page.locator('#physician-saved')).toContainText('A record with this code exists');
  await expect(page.getByLabel('Physician code', { exact: true })).not.toBeEditable();
  expect((await adminApi.get(`/api/v1/physicians?keyword=${code}`)).totalElements).toBe(1);
  expect(writes).toBe(1);
});
