import { element, ApiError, request, encounterStatuses, formatEncounterTime } from './workbench.js';
import { loadPatients, openPatient } from './patients.js';

let filters = {};
let page = 0;
let totalPages = 0;
let listController;
let filtersController;
let filtersLoaded = false;

function textNode(tag, text, className) {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    return node;
}

function renderRow(stay) {
    const row = document.createElement('tr');
    const patient = textNode('td', `${stay.lastName}, ${stay.firstName}`);
    patient.append(textNode('div', stay.medicalRecordNumber, 'record-number'),
        textNode('div', `DOB: ${stay.dateOfBirth}`, 'muted'));
    const status = document.createElement('td');
    status.append(textNode('span', encounterStatuses[stay.status] || stay.status, 'encounter-status active'));
    const placement = document.createElement('td');
    if (stay.departmentId && stay.wardId) {
        placement.append(textNode('div', `${stay.departmentName} (${stay.departmentCode})`),
            textNode('div', `${stay.wardName} (${stay.wardCode})`, 'muted'),
            textNode('div', stay.bedId ? `Bed ${stay.bedNumber}` : 'No bed assigned', 'muted'));
    } else {
        placement.textContent = stay.status === 'ADMITTED' ? 'Awaiting department entry' : 'Current placement unavailable';
    }
    const actions = document.createElement('td');
    const view = textNode('button', 'View record', 'row-action');
    view.type = 'button';
    view.setAttribute('aria-label', `View record for ${stay.firstName} ${stay.lastName}, ${stay.medicalRecordNumber}, ${stay.encounterNumber}`);
    view.addEventListener('click', () => openPatient(stay.patientId));
    actions.append(view);
    row.append(patient, textNode('td', stay.encounterNumber, 'record-number'), status,
        textNode('td', formatEncounterTime(stay.admittedAt)), placement, actions);
    return row;
}

async function loadInpatients() {
    if (element('inpatients-view').hidden) return;
    listController?.abort();
    const controller = new AbortController();
    listController = controller;
    element('inpatient-rows').replaceChildren();
    element('inpatient-list-error').hidden = true;
    element('inpatient-list-state').textContent = 'Loading inpatients…';
    element('inpatient-list-state').hidden = false;
    element('inpatient-results-summary').textContent = 'Loading inpatients…';
    element('inpatient-page-summary').textContent = 'Page —';
    element('inpatient-table-region').setAttribute('aria-busy', 'true');
    element('previous-inpatients').disabled = true;
    element('next-inpatients').disabled = true;
    const params = new URLSearchParams({ ...filters, page, size: element('inpatient-page-size').value });
    try {
        const result = await request(`./api/v1/inpatients?${params}`, {}, controller);
        if (controller !== listController) return;
        // Discharging the last patient on a page can move the final page backwards.
        if (result.items.length === 0 && page > 0) {
            page = Math.max(0, result.totalPages - 1);
            loadInpatients();
            return;
        }
        page = result.page;
        totalPages = result.totalPages;
        element('inpatient-rows').replaceChildren(...result.items.map(renderRow));
        const hasFilters = Object.values(filters).some(Boolean);
        element('inpatient-list-state').textContent = hasFilters
            ? 'No inpatients match these filters. Change the filters or clear them to see all current stays.'
            : 'No current hospital stays. Register or select a patient in Patients to record an admission.';
        element('inpatient-list-state').hidden = result.items.length > 0;
        const start = result.items.length ? page * result.size + 1 : 0;
        const end = result.items.length ? start + result.items.length - 1 : 0;
        element('inpatient-results-summary').textContent = `${start}–${end} of ${result.totalElements} ${hasFilters ? 'matching ' : ''}inpatients`;
        element('inpatient-page-summary').textContent = `Page ${totalPages ? page + 1 : 0} of ${totalPages}`;
        element('previous-inpatients').disabled = page === 0;
        element('next-inpatients').disabled = page + 1 >= totalPages;
    } catch (error) {
        if (controller !== listController) return;
        element('inpatient-results-summary').textContent = 'Inpatient list unavailable';
        element('inpatient-list-state').hidden = true;
        element('inpatient-list-error-text').textContent = error instanceof ApiError ? error.message
            : 'Could not load inpatients. Check your connection and try again.';
        element('inpatient-list-error').hidden = false;
    } finally {
        if (controller === listController) element('inpatient-table-region').setAttribute('aria-busy', 'false');
    }
}

async function loadLocationFilters() {
    filtersController?.abort();
    const controller = new AbortController();
    filtersController = controller;
    element('retry-inpatient-filters').disabled = true;
    const results = await Promise.allSettled([
        request('./api/v1/departments', {}, controller), request('./api/v1/wards', {}, controller)
    ]);
    if (controller !== filtersController) return;
    for (const [index, type] of ['department', 'ward'].entries()) {
        const result = results[index];
        if (result.status !== 'fulfilled') continue;
        const select = element(`inpatient-${type}`);
        const selected = select.value;
        const oldLabel = select.selectedOptions[0]?.textContent;
        select.replaceChildren(new Option(type === 'department' ? 'All departments' : 'All wards', ''));
        for (const value of result.value) {
            select.add(new Option(`${value[`${type}Name`]} (${value[`${type}Code`]})${value.active ? '' : ' (inactive)'}`, value.id));
        }
        // Preserve a selected filter even if it disappears from the dictionary response.
        if (selected && !result.value.some((value) => value.id === selected)) {
            select.add(new Option(oldLabel || 'Selected location unavailable', selected));
        }
        select.value = selected;
        select.disabled = false;
    }
    filtersLoaded = results.every((result) => result.status === 'fulfilled');
    element('inpatient-filter-error').hidden = filtersLoaded;
    element('retry-inpatient-filters').disabled = false;
}

element('inpatient-search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    filters = { keyword: element('inpatient-keyword').value.trim(), status: element('inpatient-status').value,
        departmentId: element('inpatient-department').value, wardId: element('inpatient-ward').value };
    page = 0;
    loadInpatients();
});
element('clear-inpatient-filters').addEventListener('click', () => {
    element('inpatient-search-form').reset();
    filters = {};
    page = 0;
    loadInpatients();
});
element('inpatient-page-size').addEventListener('change', () => { page = 0; loadInpatients(); });
element('previous-inpatients').addEventListener('click', () => { if (page > 0) { page--; loadInpatients(); } });
element('next-inpatients').addEventListener('click', () => { if (page + 1 < totalPages) { page++; loadInpatients(); } });
for (const id of ['retry-inpatients', 'refresh-inpatients']) element(id).addEventListener('click', loadInpatients);
element('retry-inpatient-filters').addEventListener('click', loadLocationFilters);
element('patient-dialog').addEventListener('close', loadInpatients);

function showView(event) {
    // In-page anchors such as Skip to content must not switch workbench views.
    if (!['', '#patients', '#inpatients'].includes(window.location.hash)) return;
    const inpatients = window.location.hash === '#inpatients';
    element('patients-view').hidden = inpatients;
    element('inpatients-view').hidden = !inpatients;
    document.title = `${inpatients ? 'Inpatients' : 'Patients'} · ClinicFlow`;
    element(inpatients ? 'inpatients-link' : 'patients-link').setAttribute('aria-current', 'page');
    element(inpatients ? 'patients-link' : 'inpatients-link').removeAttribute('aria-current');
    if (inpatients) {
        if (!filtersLoaded) loadLocationFilters();
        loadInpatients();
    } else {
        listController?.abort();
        listController = null;
        if (event) loadPatients();
    }
}
window.addEventListener('hashchange', showView);
showView();
