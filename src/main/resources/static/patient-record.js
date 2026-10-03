import { element, ApiError, request, encounterStatuses, formatEncounterTime, formatBirthDate } from './workbench.js';
import { openDepartmentAdmission, openEncounterTransfer } from './encounter-placement.js';
import { openEncounterDischarge } from './encounter-discharge.js';
import { openAdmissionCancellation } from './encounter-admission-cancellation.js';
import { openDischargeCancellation } from './encounter-discharge-cancellation.js';
import { openEncounterTimeline } from './encounter-timeline.js';
import { openEncounterPhysicians } from './encounter-physicians.js';
import { accountReady, canWrite } from './account.js';
import { createPatientAdmission } from './patient-admission.js';

export function createPatientRecord() {
    let detailController;
    let encountersController;
    let encountersPage = 0;
    let encountersTotalPages = 0;
    let selectedPatientId;

    const admission = createPatientAdmission({
        getPatientId: () => selectedPatientId,
        onSaved: refreshEncounters,
        onRefresh: refreshEncounters
    });

    function refreshEncounters() {
        encountersPage = 0;
        return loadPatientEncounters();
    }

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
            element('detail-birth-date').textContent = formatBirthDate(patient.dateOfBirth);
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

    function openPatient(id) {
        selectedPatientId = id;
        encountersPage = 0;
        admission.hide();
        element('admission-notice').hidden = true;
        element('patient-dialog').showModal();
        loadPatientDetails();
    }
    for (const id of ['close-patient', 'done-patient']) {
        element(id).addEventListener('click', () => { if (!admission.isSaving()) element('patient-dialog').close(); });
    }
    element('patient-dialog').addEventListener('cancel', (event) => { if (admission.isSaving()) event.preventDefault(); });
    element('patient-dialog').addEventListener('close', () => {
        detailController?.abort();
        detailController = null;
        encountersController?.abort();
        encountersController = null;
    });
    element('retry-patient').addEventListener('click', loadPatientDetails);

    function openEncounterAction(encounter, openAction) {
        if (admission.isSaving()) return;
        if (![openEncounterTimeline, openEncounterPhysicians].includes(openAction) && !canWrite()) return;
        admission.hide();
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
            await accountReady;
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
                const physicians = document.createElement('button');
                physicians.type = 'button';
                physicians.className = 'row-action';
                physicians.textContent = 'Physician responsibility';
                physicians.setAttribute('aria-label', `Physician responsibility for ${encounter.encounterNumber}`);
                physicians.addEventListener('click', () => openEncounterAction(encounter, openEncounterPhysicians));
                actions.append(physicians);
                if (canWrite() && encounter.status === 'DISCHARGED') {
                    const cancel = document.createElement('button');
                    cancel.type = 'button';
                    cancel.className = 'row-action';
                    cancel.textContent = 'Cancel discharge';
                    cancel.setAttribute('aria-label', `Cancel discharge for ${encounter.encounterNumber}`);
                    cancel.addEventListener('click', () => openEncounterAction(encounter, openDischargeCancellation));
                    actions.append(cancel);
                }
                if (canWrite() && ['ADMITTED', 'IN_DEPARTMENT'].includes(encounter.status)) {
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

    return { open: openPatient };
}
