import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue,
    encounterStatuses, formatEncounterTime } from './workbench.js';

const dialog = element('admission-cancellation-dialog');
const fields = { cancelledAt: 'admissionCancelledAt' };
let encounter;
let onClose;
let notice;
let contextController;
let ready = false;
let loading = false;
let saving = false;

function updateControls() {
    element('admission-cancellation-fields').disabled = !ready || saving;
    element('save-admission-cancellation').disabled = !ready || saving;
    element('refresh-admission-cancellation').disabled = loading || saving;
    for (const id of ['close-admission-cancellation', 'dismiss-admission-cancellation']) element(id).disabled = saving;
    element('save-admission-cancellation').textContent = saving ? 'Saving…' : 'Confirm cancellation';
    element('admission-cancellation-form').setAttribute('aria-busy', String(loading || saving));
}

function showState(message, error = false) {
    element('admission-cancellation-state').textContent = message;
    element('admission-cancellation-state').className = error ? 'message error' : 'message information';
    element('admission-cancellation-state').hidden = !message;
}

async function loadContext() {
    if (saving) return;
    contextController?.abort();
    const controller = new AbortController();
    contextController = controller;
    ready = false;
    loading = true;
    Object.values(fields).forEach(clearFieldError);
    element('admission-cancellation-error').hidden = true;
    showState('Checking encounter and department history…');
    updateControls();
    try {
        const timeline = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/timeline`, {}, controller);
        if (controller !== contextController) return;
        encounter = timeline.encounter;
        element('cancellation-admitted-at').textContent = formatEncounterTime(encounter.admittedAt);
        if (encounter.status === 'ADMISSION_CANCELLED') {
            showState(`This admission was cancelled at ${formatEncounterTime(encounter.admissionCancelledAt)}. Recorded operator: ${encounter.admissionCancelledBy || 'Not recorded'}. Close this form to refresh the patient record.`);
        } else if (encounter.status !== 'ADMITTED') {
            showState(`This encounter is now ${encounterStatuses[encounter.status] || encounter.status}. Only an admission awaiting department entry can be cancelled. Close this form to refresh the patient record.`);
        } else if (timeline.locations.length > 0) {
            // Closed location periods also mean department care has been recorded.
            showState('Department history exists for this encounter. The admission cannot be cancelled. Close this form to review the timeline.');
        } else {
            ready = true;
            showState('');
        }
    } catch (error) {
        if (controller !== contextController) return;
        showState(error instanceof ApiError && error.status === 404
            ? 'The encounter is no longer available. Close and reopen the patient record.'
            : 'Could not check the encounter. Use Refresh encounter to try again.', true);
    } finally {
        if (controller === contextController) {
            loading = false;
            updateControls();
        }
    }
}

export function openAdmissionCancellation(selected, patient, afterClose) {
    encounter = selected;
    onClose = afterClose;
    notice = undefined;
    element('admission-cancellation-form').reset();
    Object.values(fields).forEach(clearFieldError);
    element('admission-cancellation-error').hidden = true;
    element('cancellation-patient').textContent = patient;
    element('cancellation-encounter-number').textContent = selected.encounterNumber;
    element('cancellation-admitted-at').textContent = formatEncounterTime(selected.admittedAt);
    element('admissionCancelledAt').value = localDateTimeValue(new Date(), true);
    element('cancellation-time-hint').textContent = `Local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}). Must be on or after hospital admission and not in the future.`;
    dialog.showModal();
    loadContext();
}

for (const id of ['close-admission-cancellation', 'dismiss-admission-cancellation']) {
    element(id).addEventListener('click', () => { if (!saving) dialog.close(); });
}
dialog.addEventListener('cancel', (event) => { if (saving) event.preventDefault(); });
dialog.addEventListener('close', () => {
    contextController?.abort();
    contextController = null;
    encounter = null;
    onClose?.(notice);
    onClose = null;
});
Object.values(fields).forEach((field) => element(field).addEventListener('input', () => clearFieldError(field)));
element('refresh-admission-cancellation').addEventListener('click', loadContext);

element('admission-cancellation-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (saving || !ready) return;
    Object.values(fields).forEach(clearFieldError);
    element('admission-cancellation-error').hidden = true;
    const enteredTime = element('admissionCancelledAt').value;
    const cancelledAt = new Date(enteredTime);
    const normalizedTime = enteredTime.length === 16 ? `${enteredTime}:00` : enteredTime;
    let timeError;
    if (Number.isNaN(cancelledAt.getTime()) || localDateTimeValue(cancelledAt, true) !== normalizedTime) {
        timeError = 'Enter a valid local cancellation time.';
    } else if (cancelledAt > new Date()) {
        timeError = 'Cancellation time cannot be in the future.';
    } else if (cancelledAt < new Date(encounter.admittedAt)) {
        timeError = 'Cancellation time cannot be before hospital admission.';
    }
    if (timeError) {
        showFieldError('admissionCancelledAt', timeError);
        element('admissionCancelledAt').focus();
        return;
    }
    saving = true;
    updateControls();
    let saved;
    let firstErrorField;
    try {
        const latest = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/timeline`);
        if (latest.encounter.status !== 'ADMITTED' || latest.locations.length > 0) {
            throw new ApiError(409, { detail: 'The encounter or department history changed. Refresh the encounter and review it before saving.' });
        }
        saved = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/admission-cancellations`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ cancelledAt: cancelledAt.toISOString() })
        });
    } catch (error) {
        let message = error instanceof ApiError ? error.message
            : 'Cancellation could not be confirmed. Refresh the encounter to check its status before trying again.';
        if (!(error instanceof ApiError) || error.status >= 500) {
            message = 'Cancellation could not be confirmed. Refresh the encounter to check its status before trying again.';
        } else if (error.status === 409) {
            message = 'The encounter or department history changed. Refresh the encounter and review it before saving.';
        } else if (error.status === 404) {
            message = 'The encounter is no longer available. Close and reopen the patient record.';
        }
        for (const [name, field] of Object.entries(fields)) {
            if (error.fields?.[name]) {
                showFieldError(field, error.fields[name]);
                firstErrorField ||= field;
                message = 'Check the highlighted fields and try again.';
            }
        }
        // A lost response can follow a committed cancellation. Require a read before another write.
        if (!(error instanceof ApiError) || error.status !== 400 || !firstErrorField) ready = false;
        element('admission-cancellation-error').textContent = message;
        element('admission-cancellation-error').hidden = false;
    } finally {
        saving = false;
        updateControls();
    }
    if (saved) {
        notice = `Admission cancelled for ${saved.encounterNumber} at ${formatEncounterTime(saved.admissionCancelledAt)}. Recorded operator: ${saved.admissionCancelledBy}.`;
        dialog.close();
    } else {
        element(firstErrorField || 'admission-cancellation-error').focus();
    }
});
