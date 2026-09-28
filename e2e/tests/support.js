import { test as base, expect } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export { expect };
export const uniqueCode = prefix => `${prefix}-${randomUUID().slice(0, 12)}`;
export const minutesAgo = minutes => new Date(Date.now() - minutes * 60_000).toISOString();

async function authenticatedApi(playwright, baseURL, role) {
  const context = await playwright.request.newContext({ baseURL });
  const token = await (await context.get('/api/auth/csrf')).json();
  const response = await context.post('/api/auth/login', {
    form: { username: role, password: `e2e-${role}-only` },
    headers: { [token.headerName]: token.token }
  });
  expect(response.status()).toBe(204);
  const csrf = await (await context.get('/api/auth/csrf')).json();
  return {
    context,
    async get(path) {
      const result = await context.get(path);
      expect(result.ok(), `${path}: ${await result.text()}`).toBeTruthy();
      return result.json();
    },
    async write(path, data, method = 'POST', expectedStatus = 201) {
      const result = await context.fetch(path, {
        method, data, headers: { [csrf.headerName]: csrf.token }
      });
      expect(result.status(), `${path}: ${await result.text()}`).toBe(expectedStatus);
      return result.json();
    }
  };
}

export const test = base.extend({
  operatorApi: async ({ playwright, baseURL }, use) => {
    const api = await authenticatedApi(playwright, baseURL, 'operator');
    try { await use(api); } finally { await api.context.dispose(); }
  },
  adminApi: async ({ playwright, baseURL }, use) => {
    const api = await authenticatedApi(playwright, baseURL, 'admin');
    try { await use(api); } finally { await api.context.dispose(); }
  }
});

export async function signIn(page, role = 'operator') {
  await page.goto('/');
  await page.getByLabel('Username', { exact: true }).fill(role);
  await page.getByLabel('Password', { exact: true }).fill(`e2e-${role}-only`);
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible();
}

export async function seedEncounter(api, { placed = true, prefix = 'MRN' } = {}) {
  const patient = await api.write('/api/v1/patients', {
    medicalRecordNumber: uniqueCode(prefix), firstName: 'Browser', lastName: 'Test', dateOfBirth: '1990-01-01'
  });
  const encounter = await api.write('/api/v1/encounters', {
    patientId: patient.id, encounterNumber: uniqueCode('ENC'), admittedAt: minutesAgo(60)
  });
  const departments = await api.get('/api/v1/departments?active=true');
  const wards = await api.get('/api/v1/wards?active=true');
  const department = departments.find(item => item.departmentCode === 'DEMO-MED');
  const otherDepartment = departments.find(item => item.departmentCode === 'DEMO-REHAB');
  const ward = wards.find(item => item.wardCode === 'DEMO-WARD-1');
  const otherWard = wards.find(item => item.wardCode === 'DEMO-WARD-2');
  const location = placed ? await api.write(`/api/v1/encounters/${encounter.id}/department-admissions`, {
    departmentId: department.id, wardId: ward.id, bedId: null, startedAt: minutesAgo(50)
  }) : null;
  return { patient, encounter, department, otherDepartment, ward, otherWard, location };
}

export async function openPatient(page, medicalRecordNumber) {
  await page.getByLabel('Search patients', { exact: true }).fill(medicalRecordNumber);
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  const row = page.locator('#patient-rows tr').filter({ hasText: medicalRecordNumber });
  await row.getByRole('button', { name: /^View record for / }).click();
  await expect(page.locator('#patient-details')).toBeVisible();
}

export async function choosePlacement(page, departmentId, wardId, bedId = 'none') {
  await page.getByLabel('Department', { exact: true }).selectOption(departmentId);
  await page.getByLabel('Ward', { exact: true }).selectOption(wardId);
  await page.getByLabel('Bed assignment', { exact: true }).selectOption(bedId);
}

export async function seedPhysician(api, departmentIds) {
  return api.write('/api/v1/physicians', {
    physicianCode: uniqueCode('DR'), firstName: 'Test', lastName: 'Physician', departmentIds
  });
}

export async function selectResponsiblePhysician(page, physician) {
  await page.getByLabel('Find a physician in this department', { exact: true }).fill(physician.physicianCode);
  await page.locator('#search-responsibility').click();
  await page.getByRole('radio', { name: `Test Physician (${physician.physicianCode})`, exact: true }).check();
}
