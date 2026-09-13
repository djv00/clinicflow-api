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
    const timeout = setTimeout(() => controller.abort(), 15000);
    try {
        const response = await fetch(url, {
            ...options, signal: controller.signal, cache: 'no-store',
            headers: { Accept: 'application/json', ...options.headers }
        });
        const body = await response.json().catch(() => null);
        if (!response.ok) throw new ApiError(response.status, body);
        if (body === null) throw new Error('The server returned an unreadable response.');
        return body;
    } finally {
        clearTimeout(timeout);
    }
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
