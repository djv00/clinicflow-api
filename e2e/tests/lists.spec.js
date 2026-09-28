import { test, expect, signIn, seedEncounter, uniqueCode, minutesAgo } from './support.js';

test('inpatient pagination and combined filters use current placement and open the same responsibility history', async ({ page, operatorApi }) => {
  const prefix = uniqueCode('LIST');
  const stays = [];
  for (let index = 0; index < 11; index++) stays.push(await seedEncounter(operatorApi, { prefix }));
  const waiting = await seedEncounter(operatorApi, { prefix, placed: false });
  const moved = stays[0];
  await operatorApi.write(`/api/v1/encounters/${moved.encounter.id}/transfers`, {
    departmentId: moved.otherDepartment.id, wardId: moved.otherWard.id, bedId: null,
    transferredAt: minutesAgo(10), expectedLocationId: moved.location.id
  });
  await signIn(page);
  await page.getByRole('link', { name: 'Inpatients', exact: true }).click();
  await page.getByLabel('Search inpatients', { exact: true }).fill(prefix);
  await page.getByRole('button', { name: 'Apply filters', exact: true }).click();
  await page.locator('#inpatient-page-size').selectOption('10');
  await expect(page.locator('#inpatient-results-summary')).toContainText('of 12 matching inpatients');
  await expect(page.locator('#inpatient-rows tr')).toHaveCount(10);
  const firstPage = await page.locator('#inpatient-rows tr td:nth-child(2)').allTextContents();
  await page.locator('#next-inpatients').click();
  await expect(page.locator('#inpatient-page-summary')).toHaveText('Page 2 of 2');
  await expect(page.locator('#inpatient-rows tr')).toHaveCount(2);
  const secondPage = await page.locator('#inpatient-rows tr td:nth-child(2)').allTextContents();
  expect(new Set([...firstPage, ...secondPage]).size).toBe(12);
  await page.getByLabel('Current department', { exact: true }).selectOption(moved.department.id);
  await page.getByLabel('Current ward', { exact: true }).selectOption(moved.ward.id);
  await page.getByRole('button', { name: 'Apply filters', exact: true }).click();
  await expect(page.locator('#inpatient-page-summary')).toHaveText('Page 1 of 1');
  await expect(page.locator('#inpatient-rows tr')).toHaveCount(10);
  await expect(page.locator('#inpatient-rows')).not.toContainText(moved.encounter.encounterNumber);
  await expect(page.locator('#inpatient-rows')).not.toContainText(waiting.encounter.encounterNumber);
  await page.getByRole('button', { name: `Physician responsibility for ${stays[1].encounter.encounterNumber}`, exact: true }).click();
  await expect(page.locator('#responsibility-current')).toHaveText('No physician assigned');
  await expect(page.locator('#responsibility-department')).toContainText(moved.department.departmentName);
  await page.getByRole('button', { name: 'Close physician responsibility', exact: true }).click();
  await page.getByLabel('Current department', { exact: true }).selectOption('');
  await page.getByLabel('Current ward', { exact: true }).selectOption('');
  await page.getByLabel('Status', { exact: true }).selectOption('ADMITTED');
  await page.getByRole('button', { name: 'Apply filters', exact: true }).click();
  await expect(page.locator('#inpatient-rows tr')).toHaveCount(1);
  await expect(page.locator('#inpatient-rows')).toContainText(waiting.encounter.encounterNumber);
  await expect(page.locator('#inpatient-rows')).toContainText('Awaiting department entry');
});

test('a slower earlier patient search cannot overwrite a later search', async ({ page, operatorApi }) => {
  const first = await seedEncounter(operatorApi);
  const second = await seedEncounter(operatorApi);
  await signIn(page);
  const captured = Promise.withResolvers();
  const release = Promise.withResolvers();
  const finished = Promise.withResolvers();
  const firstQuery = url => new URL(url).searchParams.get('keyword') === first.patient.medicalRecordNumber;
  await page.route('**/api/v1/patients?**', async route => {
    if (!firstQuery(route.request().url())) return route.continue();
    const response = await route.fetch();
    captured.resolve();
    await release.promise;
    await route.fulfill({ response });
    finished.resolve();
  });
  const aborted = page.waitForEvent('requestfailed', request => firstQuery(request.url()));
  await page.getByLabel('Search patients', { exact: true }).fill(first.patient.medicalRecordNumber);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await captured.promise;
  try {
    await page.getByLabel('Search patients', { exact: true }).fill(second.patient.medicalRecordNumber);
    await page.getByRole('button', { name: 'Search', exact: true }).click();
    await expect(page.locator('#patient-rows')).toContainText(second.patient.medicalRecordNumber);
    expect((await aborted).failure().errorText).toContain('ERR_ABORTED');
  } finally { release.resolve(); }
  await finished.promise;
  await expect(page.locator('#patient-rows tr')).toHaveCount(1);
  await expect(page.locator('#patient-rows')).not.toContainText(first.patient.medicalRecordNumber);
  await expect(page.locator('#patient-rows')).toContainText(second.patient.medicalRecordNumber);
});
