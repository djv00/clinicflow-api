import { sessionRequest, requireSignIn } from './session.js';

export const element = (id) => document.getElementById(id);

export class ApiError extends Error {
    constructor(status, problem) {
        super(status >= 500 ? 'The server could not complete the request. Please try again.'
            : problem?.detail || `The request failed (${status}).`);
        this.status = status;
        this.title = problem?.title;
        this.fields = problem?.errors || {};
    }
}

export async function request(url, options = {}, controller = new AbortController()) {
    const response = await sessionRequest(url, options, controller);
    if (response.status === 401) requireSignIn();
    if (response.status === 403) {
        const session = await sessionRequest('./api/auth/session', {}, controller);
        if (session.status === 401) requireSignIn();
    }
    if (response.status === 204) return null;
    const body = response.body;
    if (!response.ok) throw new ApiError(response.status, body);
    if (body === null) throw new Error('The server returned an unreadable response.');
    return body;
}

export function clearFieldError(field) {
    element(`${field}-error`).hidden = true;
    element(field).removeAttribute('aria-invalid');
}

export function showFieldError(field, message) {
    element(`${field}-error`).textContent = message;
    element(`${field}-error`).hidden = false;
    element(field).setAttribute('aria-invalid', 'true');
}

export function localDateTimeValue(date, includeSeconds = false) {
    const pad = (value) => String(value).padStart(2, '0');
    const value = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`;
    return includeSeconds ? `${value}:${pad(date.getSeconds())}` : value;
}

export const encounterStatuses = {
    ADMITTED: 'Admitted', IN_DEPARTMENT: 'In department',
    DISCHARGED: 'Discharged', ADMISSION_CANCELLED: 'Admission cancelled'
};
const encounterTimeFormat = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit', second: '2-digit'
});
export const formatEncounterTime = (value) => value ? encounterTimeFormat.format(new Date(value)) : '—';

export async function loadPlacementDetails(location, controller) {
    const [department, ward, bed] = await Promise.all([
        request(`./api/v1/departments/${encodeURIComponent(location.departmentId)}`, {}, controller),
        request(`./api/v1/wards/${encodeURIComponent(location.wardId)}`, {}, controller),
        location.bedId ? request(`./api/v1/beds/${encodeURIComponent(location.bedId)}`, {}, controller) : null
    ]);
    return { department, ward, bed };
}
