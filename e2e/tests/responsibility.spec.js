import { test, expect, signIn, seedEncounter, openPatient, choosePlacement,
  seedPhysician, selectResponsiblePhysician, minutesAgo } from './support.js';

test('operator assigns, hands over and releases responsibility while preserving history', async ({ page, operatorApi, adminApi }) => {
  const { patient, encounter, department } = await seedEncounter(operatorApi);
  const first = await seedPhysician(adminApi, [department.id]);
  const second = await seedPhysician(adminApi, [department.id]);
  await signIn(page);
  await openPatient(page, patient.medicalRecordNumber);
  await page.getByRole('button', { name: `Physician responsibility for ${encounter.encounterNumber}`, exact: true }).click();
  await page.getByRole('button', { name: 'Assign physician', exact: true }).click();
  await selectResponsiblePhysician(page, first);
  await page.getByRole('button', { name: 'Save assignment', exact: true }).click();
  await expect(page.locator('#responsibility-current')).toContainText(first.physicianCode);
  await page.getByRole('button', { name: 'Hand over responsibility', exact: true }).click();
  await selectResponsiblePhysician(page, second);
  await page.getByRole('button', { name: 'Save handover', exact: true }).click();
  await expect(page.locator('#responsibility-current')).toContainText(second.physicianCode);
  await expect(page.locator('#responsibility-history')).toContainText('Handed over');
  await page.getByRole('button', { name: 'Release physician', exact: true }).click();
  await page.getByRole('button', { name: 'Confirm release', exact: true }).click();
  await expect(page.locator('#responsibility-current')).toHaveText('No physician assigned');
  await expect(page.locator('#responsibility-history tr')).toHaveCount(2);
  await expect(page.locator('#responsibility-history')).toContainText('Released');
  const state = await operatorApi.get(`/api/v1/encounters/${encounter.id}/physician-assignments`);
  expect(state.currentAssignmentId).toBeNull();
  expect(state.assignments.map(item => item.endReason).sort()).toEqual(['REASSIGNED', 'RELEASED']);
  expect(state.assignments.every(item => item.assignedBy === 'operator' && item.endedBy === 'operator')).toBeTruthy();
});

for (const operation of ['transfer', 'discharge']) {
  test(operation === 'transfer' ? 'department transfer closes physician responsibility'
    : 'discharge closes responsibility and correction requires a new physician selection', async ({ page, operatorApi, adminApi }) => {
    const { patient, encounter, department, otherDepartment, otherWard, location } = await seedEncounter(operatorApi);
    const physician = await seedPhysician(adminApi, [department.id]);
    await operatorApi.write(`/api/v1/encounters/${encounter.id}/physician-assignments`, {
      physicianId: physician.id, expectedLocationId: location.id, expectedAssignmentId: null, startedAt: minutesAgo(40)
    });
    await signIn(page);
    await openPatient(page, patient.medicalRecordNumber);
    if (operation === 'transfer') {
      await page.getByRole('button', { name: `Transfer for ${encounter.encounterNumber}`, exact: true }).click();
      await choosePlacement(page, otherDepartment.id, otherWard.id);
      await page.getByRole('button', { name: 'Save transfer', exact: true }).click();
      await expect(page.locator('#department-dialog')).not.toBeVisible();
    } else {
      await page.getByRole('button', { name: `Discharge for ${encounter.encounterNumber}`, exact: true }).click();
      await page.getByRole('button', { name: 'Save discharge', exact: true }).click();
      await expect(page.locator('#discharge-dialog')).not.toBeVisible();
    }
    await page.getByRole('button', { name: `Physician responsibility for ${encounter.encounterNumber}`, exact: true }).click();
    await expect(page.locator('#responsibility-current')).toHaveText(operation === 'transfer' ? 'No physician assigned' : 'No current responsibility');
    await expect(page.locator('#responsibility-history')).toContainText(operation === 'transfer' ? 'Department transfer' : 'Discharged');
    const state = await operatorApi.get(`/api/v1/encounters/${encounter.id}/physician-assignments`);
    expect(state.currentAssignmentId).toBeNull();
    expect(state.assignments).toHaveLength(1);
    expect(state.assignments[0].endReason).toBe(operation === 'transfer' ? 'DEPARTMENT_TRANSFER' : 'DISCHARGE');
    expect(state.assignments[0].endedBy).toBe('operator');
    if (operation === 'discharge') {
      await page.getByRole('button', { name: 'Close physician responsibility', exact: true }).click();
      await page.getByRole('button', { name: `Cancel discharge for ${encounter.encounterNumber}`, exact: true }).click();
      await page.getByRole('button', { name: 'Confirm cancellation', exact: true }).click();
      await expect(page.locator('#discharge-cancellation-dialog')).not.toBeVisible();
      await page.getByRole('button', { name: `Physician responsibility for ${encounter.encounterNumber}`, exact: true }).click();
      await expect(page.locator('#responsibility-status')).toHaveText('In department');
      await expect(page.locator('#responsibility-current')).toHaveText('No physician assigned');
      await expect(page.getByRole('button', { name: 'Assign physician', exact: true })).toBeVisible();
      const restored = await operatorApi.get(`/api/v1/encounters/${encounter.id}/physician-assignments`);
      expect(restored.currentAssignmentId).toBeNull();
      expect(restored.assignments).toEqual(state.assignments);
      expect(restored.currentLocation.id).not.toBe(location.id);
    }
  });
}
