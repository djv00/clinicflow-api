import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue,
    encounterStatuses, formatEncounterTime } from './workbench.js';
import { openDepartmentAdmission, openEncounterTransfer } from './encounter-placement.js';
import { openEncounterDischarge } from './encounter-discharge.js';
import { openAdmissionCancellation } from './encounter-admission-cancellation.js';
import { openEncounterTimeline } from './encounter-timeline.js';
const dateFormat = new Intl.DateTimeFormat('en-CA', {
    year: 'numeric', month: 'short', day: 'numeric', timeZone: 'UTC'
});
const formatDate = (value) => dateFormat.format(new Date(`${value}T00:00:00Z`));
const registrationFields = ['medicalRecordNumber', 'firstName', 'lastName', 'dateOfBirth'];
const admissionFields = ['encounterNumber', 'admittedAt'];
let keyword = '';
let page = 0;
let totalPages = 0;
let listVersion = 0;
let listController;
let detailController;
let encountersController;
let encountersPage = 0;
let encountersTotalPages = 0;
let selectedPatientId;
let saving = false;
let admitting = false;

function setListState(title, detail = '') {
    element('list-state-title').textContent = title;
    element('list-state-detail').textContent = detail;
    element('list-state').hidden = false;
}

export async function loadPatients() {
    const version = ++listVersion;
    listController?.abort();
    listController = new AbortController();
    element('patient-rows').replaceChildren();
    element('list-error').hidden = true;
    element('table-region').setAttribute('aria-busy', 'true');
    element('previous-page').disabled = true;
    element('next-page').disabled = true;
    element('results-summary').textContent = 'Loading patients…';
    element('page-summary').textContent = 'Page —';
    setListState('Loading patients…');
    const params = new URLSearchParams({ keyword, page, size: element('page-size').value });
    try {
        const result = await request(`./api/v1/patients?${params}`, {}, listController);
        // A later search must not be overwritten by an earlier response.
        if (version !== listVersion) return;
        page = result.page;
        totalPages = result.totalPages;
        const fragment = document.createDocumentFragment();
        for (const patient of result.items) {
            const row = document.createElement('tr');
            const name = `${patient.lastName}, ${patient.firstName}`;
            for (const value of [name, patient.medicalRecordNumber, formatDate(patient.dateOfBirth)]) {
                const cell = document.createElement('td');
                cell.textContent = value;
                row.append(cell);
            }
            row.children[1].className = 'record-number';
            const actions = document.createElement('td');
            const view = document.createElement('button');
            view.type = 'button';
            view.className = 'row-action';
            view.textContent = 'View record';
            view.setAttribute('aria-label', `View record for ${name}, ${patient.medicalRecordNumber}`);
            view.addEventListener('click', () => openPatient(patient.id));
            actions.append(view);
            row.append(actions);
            fragment.append(row);
        }
        element('patient-rows').replaceChildren(fragment);
        element('list-state').hidden = result.items.length > 0;
        if (result.items.length === 0) {
            if (result.totalElements > 0) setListState('No patients on this page', 'Use Previous to return to an earlier page.');
            else if (keyword) setListState('No matching patients', 'Try another name or medical record number, or clear the search.');
            else setListState('No patients registered yet', 'Register a patient to start building the directory.');
        }
        const start = result.items.length ? page * result.size + 1 : 0;
        const end = result.items.length ? start + result.items.length - 1 : 0;
        element('results-summary').textContent = `${start}–${end} of ${result.totalElements} ${keyword ? 'matching patients' : 'patients'}`;
        element('page-summary').textContent = `Page ${totalPages ? page + 1 : 0} of ${totalPages}`;
        element('previous-page').disabled = page === 0;
        element('next-page').disabled = page + 1 >= totalPages;
    } catch (error) {
        if (version !== listVersion) return;
        element('results-summary').textContent = 'Patient list unavailable';
        element('list-state').hidden = true;
        element('list-error-text').textContent = error instanceof ApiError ? error.message
            : 'Could not load patients. Check your connection and try again.';
        element('list-error').hidden = false;
    } finally {
        if (version === listVersion) element('table-region').setAttribute('aria-busy', 'false');
    }
}

element('search-form').addEventListener('submit', (event) => {
    event.preventDefault();
    keyword = element('keyword').value.trim();
    page = 0;
    element('notice').hidden = true;
    loadPatients();
});
element('clear-search').addEventListener('click', () => {
    keyword = '';
    element('keyword').value = '';
    page = 0;
    element('notice').hidden = true;
    loadPatients();
    element('keyword').focus();
});
element('page-size').addEventListener('change', () => { page = 0; loadPatients(); });
element('previous-page').addEventListener('click', () => { if (page > 0) { page--; loadPatients(); } });
element('next-page').addEventListener('click', () => { if (page + 1 < totalPages) { page++; loadPatients(); } });
element('retry-list').addEventListener('click', loadPatients);

element('register-patient').addEventListener('click', () => {
    element('registration-form').reset();
    registrationFields.forEach(clearFieldError);
    element('registration-error').hidden = true;
    const yesterday = new Date();
    yesterday.setDate(yesterday.getDate() - 1);
    element('dateOfBirth').max = `${yesterday.getFullYear()}-${String(yesterday.getMonth() + 1).padStart(2, '0')}-${String(yesterday.getDate()).padStart(2, '0')}`;
    element('registration-dialog').showModal();
    element('medicalRecordNumber').focus();
});
for (const id of ['close-registration', 'cancel-registration']) {
    element(id).addEventListener('click', () => { if (!saving) element('registration-dialog').close(); });
}
element('registration-dialog').addEventListener('cancel', (event) => { if (saving) event.preventDefault(); });
registrationFields.forEach((field) => element(field).addEventListener('input', () => clearFieldError(field)));

element('registration-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (saving) return;
    registrationFields.forEach(clearFieldError);
    const body = Object.fromEntries(registrationFields.map((field) => [field, element(field).value.trim()]));
    saving = true;
    element('registration-fields').disabled = true;
    for (const id of ['save-patient', 'close-registration', 'cancel-registration']) element(id).disabled = true;
    element('save-patient').textContent = 'Saving…';
    element('registration-error').hidden = true;
    let patient;
    let firstErrorField;
    try {
        patient = await request('./api/v1/patients', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
    } catch (error) {
        let message = error instanceof ApiError ? error.message
            : 'Could not confirm registration. Search for this medical record number before trying again.';
        if (error instanceof ApiError && error.status >= 500) {
            message = 'Could not confirm registration. Search for this medical record number before trying again.';
        }
        if (error instanceof ApiError && error.status === 409) {
            showFieldError('medicalRecordNumber', 'This medical record number is already registered.');
            firstErrorField = 'medicalRecordNumber';
            message = 'A patient with this medical record number already exists. Cancel and search for the existing record.';
        }
        for (const field of registrationFields) {
            if (error.fields?.[field]) {
                showFieldError(field, error.fields[field]);
                firstErrorField ||= field;
                message = 'Check the highlighted fields and try again.';
            }
        }
        element('registration-error').textContent = message;
        element('registration-error').hidden = false;
    } finally {
        saving = false;
        element('registration-fields').disabled = false;
        for (const id of ['save-patient', 'close-registration', 'cancel-registration']) element(id).disabled = false;
        element('save-patient').textContent = 'Save patient';
    }
    if (firstErrorField) element(firstErrorField).focus();
    if (patient) {
        element('registration-dialog').close();
        element('notice').textContent = `Registered ${patient.firstName} ${patient.lastName} (${patient.medicalRecordNumber}). The directory is now filtered by this record number.`;
        element('notice').hidden = false;
        keyword = patient.medicalRecordNumber;
        element('keyword').value = keyword;
        page = 0;
        loadPatients();
    }
});

async function loadPatientDetails() {
    detailController?.abort();
    encountersController?.abort();
    encountersController = null;
    element('patient-encounters').hidden = true;
    const controller = new AbortController();
    detailController = controller;
    element('patient-details').hidden = true;
    element('retry-patient').hidden = true;
    element('patient-status').hidden = false;
    element('patient-status').textContent = 'Loading patient…';
    element('patient-status').className = '';
    try {
        const patient = await request(`./api/v1/patients/${encodeURIComponent(selectedPatientId)}`, {}, controller);
        if (controller !== detailController) return;
        element('detail-record-number').textContent = patient.medicalRecordNumber;
        element('detail-first-name').textContent = patient.firstName;
        element('detail-last-name').textContent = patient.lastName;
        element('detail-birth-date').textContent = formatDate(patient.dateOfBirth);
        element('patient-details').hidden = false;
        element('patient-status').hidden = true;
        element('patient-encounters').hidden = false;
        loadPatientEncounters();
    } catch (error) {
        if (controller !== detailController) return;
        element('patient-status').textContent = error instanceof ApiError ? error.message
            : 'Could not load this patient. Check your connection and try again.';
        element('patient-status').className = 'message error';
        element('retry-patient').hidden = false;
    }
}

export function openPatient(id) {
    selectedPatientId = id;
    encountersPage = 0;
    hideAdmissionForm();
    element('admission-notice').hidden = true;
    element('patient-dialog').showModal();
    loadPatientDetails();
}
for (const id of ['close-patient', 'done-patient']) {
    element(id).addEventListener('click', () => { if (!admitting) element('patient-dialog').close(); });
}
element('patient-dialog').addEventListener('cancel', (event) => { if (admitting) event.preventDefault(); });
element('patient-dialog').addEventListener('close', () => {
    detailController?.abort();
    detailController = null;
    encountersController?.abort();
    encountersController = null;
});
element('retry-patient').addEventListener('click', loadPatientDetails);

function hideAdmissionForm() {
    element('admission-form').hidden = true;
    element('admit-patient').hidden = false;
    element('admit-patient').setAttribute('aria-expanded', 'false');
}

element('admit-patient').addEventListener('click', () => {
    element('admission-form').reset();
    admissionFields.forEach(clearFieldError);
    element('admission-error').hidden = true;
    element('admission-notice').hidden = true;
    element('refresh-admission-records').hidden = true;
    element('admittedAt').value = localDateTimeValue(new Date());
    element('admission-time-hint').textContent = `Local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}). Future times are not allowed.`;
    element('admission-form').hidden = false;
    element('admit-patient').setAttribute('aria-expanded', 'true');
    element('admit-patient').hidden = true;
    element('encounterNumber').focus();
});
element('cancel-admission').addEventListener('click', () => {
    if (admitting) return;
    hideAdmissionForm();
    element('admit-patient').focus();
});
admissionFields.forEach((field) => element(field).addEventListener('input', () => clearFieldError(field)));
element('refresh-admission-records').addEventListener('click', () => {
    encountersPage = 0;
    loadPatientEncounters();
});

element('admission-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (admitting) return;
    admissionFields.forEach(clearFieldError);
    element('admission-error').hidden = true;
    element('refresh-admission-records').hidden = true;
    const encounterNumber = element('encounterNumber').value.trim();
    const enteredTime = element('admittedAt').value;
    const admittedAt = new Date(enteredTime);
    if (!encounterNumber) {
        showFieldError('encounterNumber', 'Encounter number is required.');
        element('encounterNumber').focus();
        return;
    }
    // Reject local times that the clock skips when daylight saving time starts.
    if (Number.isNaN(admittedAt.getTime()) || localDateTimeValue(admittedAt) !== enteredTime) {
        showFieldError('admittedAt', 'Enter a valid local admission time.');
        element('admittedAt').focus();
        return;
    }
    if (admittedAt > new Date()) {
        showFieldError('admittedAt', 'Admission time cannot be in the future.');
        element('admittedAt').focus();
        return;
    }
    admitting = true;
    element('admission-fields').disabled = true;
    element('admission-form').setAttribute('aria-busy', 'true');
    const buttons = ['save-admission', 'cancel-admission', 'close-patient', 'done-patient'];
    buttons.forEach((id) => { element(id).disabled = true; });
    element('save-admission').textContent = 'Saving…';
    let encounter;
    let firstErrorField;
    try {
        encounter = await request('./api/v1/encounters', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ patientId: selectedPatientId, encounterNumber, admittedAt: admittedAt.toISOString() })
        });
    } catch (error) {
        const uncertain = !(error instanceof ApiError) || error.status >= 500;
        let message = uncertain
            ? 'Admission could not be confirmed. Refresh encounters and check this encounter number before trying again.'
            : error.message;
        element('refresh-admission-records').hidden = !(uncertain || error.status === 409);
        const fieldErrors = { ...error.fields };
        if (error.status === 409 && error.title === 'Duplicate encounter number') {
            fieldErrors.encounterNumber = 'This encounter number is already in use. Check the existing record or use a new number.';
        } else if (error.status === 409 && error.title === 'Active encounter already exists') {
            message = 'This patient already has an active hospital encounter. Review the existing encounter before admitting again.';
        } else if (error.status === 409 && error.title === 'Encounter history conflict') {
            fieldErrors.admittedAt = 'Admission time cannot be before a previous discharge for this patient.';
        } else if (error.status === 400 && error.title === 'Invalid admission time') {
            fieldErrors.admittedAt = 'Admission time cannot be in the future.';
        } else if (error.status === 404) {
            message = 'This patient record is no longer available. Close and reopen the patient record.';
        }
        for (const field of admissionFields) {
            if (fieldErrors[field]) {
                showFieldError(field, fieldErrors[field]);
                firstErrorField ||= field;
                message = 'Check the highlighted fields and try again.';
            }
        }
        element('admission-error').textContent = message;
        element('admission-error').hidden = false;
    } finally {
        admitting = false;
        element('admission-fields').disabled = false;
        element('admission-form').setAttribute('aria-busy', 'false');
        buttons.forEach((id) => { element(id).disabled = false; });
        element('save-admission').textContent = 'Save admission';
    }
    if (encounter) {
        hideAdmissionForm();
        element('admission-notice').textContent = `Admission saved: ${encounter.encounterNumber}. Status: Admitted.`;
        element('admission-notice').hidden = false;
        element('admission-notice').focus();
        encountersPage = 0;
        loadPatientEncounters();
    } else {
        element(firstErrorField || 'admission-error').focus();
    }
});

function openEncounterAction(encounter, openAction) {
    if (admitting) return;
    hideAdmissionForm();
    const patient = `${element('detail-first-name').textContent} ${element('detail-last-name').textContent} (${element('detail-record-number').textContent})`;
    openAction(encounter, patient, (notice) => {
        if (notice) {
            element('admission-notice').textContent = notice;
            element('admission-notice').hidden = false;
            element('admission-notice').focus();
        }
        loadPatientEncounters();
    });
}

async function loadPatientEncounters() {
    encountersController?.abort();
    const controller = new AbortController();
    encountersController = controller;
    element('encounter-rows').replaceChildren();
    element('encounters-table-region').hidden = true;
    element('encounters-state').hidden = false;
    element('encounters-state').className = '';
    element('encounters-state').textContent = 'Loading encounters…';
    element('encounters-page-summary').textContent = '';
    element('retry-encounters').hidden = true;
    element('previous-encounters').disabled = true;
    element('next-encounters').disabled = true;
    try {
        const params = new URLSearchParams({ page: encountersPage, size: 5 });
        const result = await request(`./api/v1/patients/${encodeURIComponent(selectedPatientId)}/encounters?${params}`, {}, controller);
        if (controller !== encountersController) return;
        encountersPage = result.page;
        encountersTotalPages = result.totalPages;
        const fragment = document.createDocumentFragment();
        for (const encounter of result.items) {
            const row = document.createElement('tr');
            const number = document.createElement('td');
            number.textContent = encounter.encounterNumber;
            number.className = 'record-number';
            const status = document.createElement('td');
            const badge = document.createElement('span');
            badge.className = 'encounter-status';
            if (['ADMITTED', 'IN_DEPARTMENT'].includes(encounter.status)) badge.classList.add('active');
            badge.textContent = encounterStatuses[encounter.status] || encounter.status;
            status.append(badge);
            const admitted = document.createElement('td');
            admitted.textContent = formatEncounterTime(encounter.admittedAt);
            const ended = document.createElement('td');
            ended.textContent = formatEncounterTime(encounter.status === 'ADMISSION_CANCELLED'
                ? encounter.admissionCancelledAt : encounter.dischargedAt);
            const actions = document.createElement('td');
            const timeline = document.createElement('button');
            timeline.type = 'button';
            timeline.className = 'row-action';
            timeline.textContent = 'Timeline';
            timeline.setAttribute('aria-label', `Timeline for ${encounter.encounterNumber}`);
            timeline.addEventListener('click', () => openEncounterAction(encounter, openEncounterTimeline));
            actions.append(timeline);
            if (encounter.status === 'ADMITTED' || encounter.status === 'IN_DEPARTMENT') {
                const transfer = encounter.status === 'IN_DEPARTMENT';
                const enter = document.createElement('button');
                enter.type = 'button';
                enter.className = 'row-action';
                enter.textContent = transfer ? 'Transfer' : 'Enter department';
                enter.setAttribute('aria-label', `${enter.textContent} for ${encounter.encounterNumber}`);
                enter.addEventListener('click', () => {
                    openEncounterAction(encounter, transfer ? openEncounterTransfer : openDepartmentAdmission);
                });
                actions.append(enter);
                if (transfer) {
                    const discharge = document.createElement('button');
                    discharge.type = 'button';
                    discharge.className = 'row-action';
                    discharge.textContent = 'Discharge';
                    discharge.setAttribute('aria-label', `Discharge for ${encounter.encounterNumber}`);
                    discharge.addEventListener('click', () => openEncounterAction(encounter, openEncounterDischarge));
                    actions.append(discharge);
                } else {
                    const cancel = document.createElement('button');
                    cancel.type = 'button';
                    cancel.className = 'row-action';
                    cancel.textContent = 'Cancel admission';
                    cancel.setAttribute('aria-label', `Cancel admission for ${encounter.encounterNumber}`);
                    cancel.addEventListener('click', () => openEncounterAction(encounter, openAdmissionCancellation));
                    actions.append(cancel);
                }
            }
            row.append(number, status, admitted, ended, actions);
            fragment.append(row);
        }
        element('encounter-rows').replaceChildren(fragment);
        element('encounters-table-region').hidden = result.items.length === 0;
        element('encounters-state').hidden = result.items.length > 0;
        element('encounters-state').textContent = result.totalElements === 0
            ? 'No hospital encounters recorded for this patient.' : 'No encounters on this page.';
        element('encounters-page-summary').textContent = `${result.totalElements} ${result.totalElements === 1 ? 'encounter' : 'encounters'} · Page ${result.totalPages ? result.page + 1 : 0} of ${result.totalPages}`;
        element('previous-encounters').disabled = result.page === 0;
        element('next-encounters').disabled = result.page + 1 >= result.totalPages;
    } catch (error) {
        if (controller !== encountersController) return;
        element('encounters-state').textContent = error instanceof ApiError ? error.message
            : 'Could not load hospital encounters. Check your connection and try again.';
        element('encounters-state').className = 'message error';
        element('retry-encounters').hidden = false;
    }
}

element('retry-encounters').addEventListener('click', loadPatientEncounters);
element('previous-encounters').addEventListener('click', () => {
    if (encountersPage > 0) { encountersPage--; loadPatientEncounters(); }
});
element('next-encounters').addEventListener('click', () => {
    if (encountersPage + 1 < encountersTotalPages) { encountersPage++; loadPatientEncounters(); }
});

loadPatients();
