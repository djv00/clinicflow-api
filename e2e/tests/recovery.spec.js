import { test, expect, signIn, seedEncounter, openPatient, choosePlacement, minutesAgo, uniqueCode } from './support.js';

test('admission recovery uses the error code even when presentation text changes', async ({ page, operatorApi }) => {
  const existing = await seedEncounter(operatorApi);
  const patient = await operatorApi.write('/api/v1/patients', {
    medicalRecordNumber: uniqueCode('MRN'), firstName: 'Error', lastName: 'Contract', dateOfBirth: '1990-01-01'
  });
  await signIn(page);
  await openPatient(page, patient.medicalRecordNumber);
  await page.getByRole('button', { name: 'Admit patient', exact: true }).click();
  await page.getByLabel('Encounter number', { exact: true }).fill(existing.encounter.encounterNumber);
  await page.route('**/api/v1/encounters', async route => {
    const response = await route.fetch();
    expect(response.status()).toBe(409);
    const problem = await response.json();
    expect(problem.code).toBe('DUPLICATE_ENCOUNTER_NUMBER');
    // Keep the real business rejection and code; only its presentation wording changes.
    await route.fulfill({ response, json: { ...problem, title: 'Updated title', detail: 'Updated explanation' } });
  }, { times: 1 });
  await page.getByRole('button', { name: 'Save admission', exact: true }).click();
  await expect(page.locator('#encounterNumber-error')).toContainText('already in use');
  expect((await operatorApi.get(`/api/v1/patients/${patient.id}/encounters`)).totalElements).toBe(0);
});

test('a transfer committed after the page precheck rejects the stale write and requires refresh', async ({ page, operatorApi }) => {
  const care = await seedEncounter(operatorApi);
  const { patient, encounter, department, otherDepartment, ward, otherWard, location } = care;
  const path = `/api/v1/encounters/${encounter.id}/transfers`;
  await signIn(page);
  await openPatient(page, patient.medicalRecordNumber);
  await page.getByRole('button', { name: `Transfer for ${encounter.encounterNumber}`, exact: true }).click();
  await choosePlacement(page, otherDepartment.id, ward.id);
  // Interleave only after the browser's GET precheck, exercising the backend's expected-location check.
  await page.route(`**${path}`, async route => {
    await operatorApi.write(path, { departmentId: department.id, wardId: otherWard.id,
      bedId: null, transferredAt: minutesAgo(10), expectedLocationId: location.id });
    await route.continue();
  }, { times: 1 });
  const rejected = page.waitForResponse(response => response.url().endsWith(path) && response.status() === 409);
  await page.getByRole('button', { name: 'Save transfer', exact: true }).click();
  await rejected;
  await expect(page.locator('#department-error')).toContainText('Refresh availability');
  await expect(page.getByRole('button', { name: 'Save transfer', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Refresh availability', exact: true }).click();
  await expect(page.locator('#current-ward')).toContainText(otherWard.wardName);
  await expect(page.locator('#placement-state')).toContainText('choose a destination again');
  await expect(page.getByLabel('Department', { exact: true })).toHaveValue('');
  const timeline = await operatorApi.get(`/api/v1/encounters/${encounter.id}/timeline`);
  expect(timeline.locations).toHaveLength(2);
  expect(timeline.locations.find(item => item.endedAt === null).wardId).toBe(otherWard.id);
});

test('a committed discharge with a lost response is recovered without a second write', async ({ page, operatorApi }) => {
  const { patient, encounter } = await seedEncounter(operatorApi);
  const path = `/api/v1/encounters/${encounter.id}/discharges`;
  await signIn(page);
  await openPatient(page, patient.medicalRecordNumber);
  await page.getByRole('button', { name: `Discharge for ${encounter.encounterNumber}`, exact: true }).click();
  let writes = 0;
  const committed = Promise.withResolvers();
  const release = Promise.withResolvers();
  await page.route(`**${path}`, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    writes++;
    const response = await route.fetch();
    committed.resolve(response.status());
    await release.promise;
    await route.abort('failed');
  });
  await page.getByRole('button', { name: 'Save discharge', exact: true }).click();
  try {
    expect(await committed.promise).toBe(200);
    await expect(page.locator('#save-discharge')).toBeDisabled();
    await expect(page.locator('#refresh-discharge')).toBeDisabled();
  } finally { release.resolve(); }
  await expect(page.locator('#discharge-error')).toContainText('could not be confirmed');
  await expect(page.locator('#save-discharge')).toBeDisabled();
  await page.getByRole('button', { name: 'Refresh encounter', exact: true }).click();
  await expect(page.locator('#discharge-state')).toContainText('This encounter was discharged');
  await expect(page.locator('#save-discharge')).toBeDisabled();
  const timeline = await operatorApi.get(`/api/v1/encounters/${encounter.id}/timeline`);
  expect(timeline.encounter.status).toBe('DISCHARGED');
  expect(timeline.discharges).toHaveLength(1);
  expect(timeline.locations.filter(item => item.endedAt === null)).toHaveLength(0);
  expect(writes).toBe(1);
});

test('failed patient search can be retried against the backend', async ({ page, operatorApi }) => {
  const { patient } = await seedEncounter(operatorApi);
  await signIn(page);
  const path = '**/api/v1/patients?**';
  await page.route(path, route => route.abort('failed'));
  await page.getByLabel('Search patients', { exact: true }).fill(patient.medicalRecordNumber);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.locator('#list-error')).toBeVisible();
  await page.unroute(path);
  await page.locator('#retry-list').click();
  await expect(page.locator('#list-error')).toBeHidden();
  await expect(page.locator('#patient-rows tr')).toHaveCount(1);
  await expect(page.locator('#patient-rows')).toContainText(patient.medicalRecordNumber);
});
