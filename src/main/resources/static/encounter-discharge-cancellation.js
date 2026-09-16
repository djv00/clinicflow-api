import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue,
    encounterStatuses, formatEncounterTime, loadPlacementDetails } from './workbench.js';

const dialog = element('discharge-cancellation-dialog');
const fields = { cancelledAt: 'dischargeCancelledAt' };
let encounter;
let discharge;
let onClose;
let notice;
let contextController;
let ready = false;
let loading = false;
let saving = false;

function updateControls() {
    element('discharge-cancellation-fields').disabled = !ready || saving;
    element('save-discharge-cancellation').disabled = !ready || saving;
    element('refresh-discharge-cancellation').disabled = loading || saving;
    for (const id of ['close-discharge-cancellation', 'dismiss-discharge-cancellation']) element(id).disabled = saving;
    element('save-discharge-cancellation').textContent = saving ? 'Saving…' : 'Confirm cancellation';
    element('discharge-cancellation-form').setAttribute('aria-busy', String(loading || saving));
}

function showState(message, error = false) {
    element('discharge-cancellation-state').textContent = message;
    element('discharge-cancellation-state').className = error ? 'message error' : 'message information';
    element('discharge-cancellation-state').hidden = !message;
}

function recordedDischarge(timeline) {
    if (timeline.encounter.status !== 'DISCHARGED' || timeline.locations.some((item) => item.endedAt === null)) return null;
    const records = timeline.discharges.filter((item) => item.cancelledAt === null);
    if (records.length !== 1) return null;
    const record = records[0];
    const location = timeline.locations.find((item) => item.id === record.locationId);
    const time = new Date(record.dischargedAt).getTime();
    if (!location?.endedAt || !timeline.encounter.dischargedAt
        || time !== new Date(location.endedAt).getTime()
        || time !== new Date(timeline.encounter.dischargedAt).getTime()) return null;
    return { record, location };
}

async function loadContext() {
    if (saving) return;
    contextController?.abort();
    const controller = new AbortController();
    contextController = controller;
    ready = false;
    loading = true;
    Object.values(fields).forEach(clearFieldError);
    element('discharge-cancellation-error').hidden = true;
    element('restoration-placement').hidden = true;
    showState('Checking discharge and original placement…');
    updateControls();
    try {
        const timeline = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/timeline`, {}, controller);
        if (controller !== contextController) return;
        encounter = timeline.encounter;
        element('restoration-discharged-at').textContent = formatEncounterTime(encounter.dischargedAt);
        if (encounter.status !== 'DISCHARGED') {
            const cancelled = timeline.discharges.find((item) => item.id === discharge?.id && item.cancelledAt);
            showState(cancelled
                ? `This discharge was already cancelled. Recorded operator: ${cancelled.cancelledBy || 'Not recorded'}; cancellation time: ${formatEncounterTime(cancelled.cancelledAt)}. Close this form to review the patient record.`
                : `This encounter is now ${encounterStatuses[encounter.status] || encounter.status}. Only a discharged encounter can be restored. Close this form to refresh the patient record.`);
            return;
        }
        const current = recordedDischarge(timeline);
        if (!current) {
            showState('The discharge could not be matched to its original placement. Refresh the encounter before continuing.', true);
            return;
        }
        const { department, ward, bed } = await loadPlacementDetails(current.location, controller);
        if (controller !== contextController) return;
        const changed = discharge && discharge.id !== current.record.id;
        discharge = current.record;
        const inactive = (value) => value.active ? '' : ' (inactive)';
        element('restoration-department').textContent = `${department.departmentName} (${department.departmentCode})${inactive(department)}`;
        element('restoration-ward').textContent = `${ward.wardName} (${ward.wardCode})${inactive(ward)}`;
        element('restoration-bed').textContent = bed ? `Bed ${bed.bedNumber}${inactive(bed)}` : 'No bed assigned';
        element('restoration-effective-at').textContent = formatEncounterTime(discharge.dischargedAt);
        element('restoration-placement').hidden = false;
        if (!department.active || !ward.active || (bed && !bed.active)) {
            showState('The original department, ward, or bed is inactive. This discharge cannot be cancelled while that placement is unavailable.', true);
            return;
        }
        ready = true;
        showState(changed ? 'A different discharge is now recorded. Review its time and placement before confirming cancellation.' : '');
    } catch (error) {
        if (controller !== contextController) return;
        showState(error instanceof ApiError && error.status === 404
            ? 'The encounter or its original location is no longer available. Close and reopen the patient record.'
            : 'Could not load the discharge or original placement. Use Refresh encounter to try again.', true);
    } finally {
        if (controller === contextController) {
            loading = false;
            updateControls();
        }
    }
}

export function openDischargeCancellation(selected, patient, afterClose) {
    encounter = selected;
    discharge = null;
    onClose = afterClose;
    notice = undefined;
    element('discharge-cancellation-form').reset();
    Object.values(fields).forEach(clearFieldError);
    element('discharge-cancellation-error').hidden = true;
    element('restoration-patient').textContent = patient;
    element('restoration-encounter-number').textContent = selected.encounterNumber;
    element('restoration-discharged-at').textContent = formatEncounterTime(selected.dischargedAt);
    element('dischargeCancelledAt').value = localDateTimeValue(new Date(), true);
    element('discharge-cancellation-time-hint').textContent = `Local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}). Must be on or after discharge and not in the future.`;
    dialog.showModal();
    loadContext();
}

for (const id of ['close-discharge-cancellation', 'dismiss-discharge-cancellation']) {
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
element('refresh-discharge-cancellation').addEventListener('click', loadContext);

function cancellationError(error) {
    if (!(error instanceof ApiError) || error.status >= 500) {
        return 'Cancellation could not be confirmed. Refresh the encounter to check its status before trying again.';
    }
    if (error.status === 404) return 'The encounter or original location is no longer available. Close and reopen the patient record.';
    if (error.title === 'Active encounter already exists') return 'This patient has another active hospital stay. Review their encounter history before correcting this discharge.';
    if (error.title === 'Encounter history conflict') {
        return error.message.startsWith('Bed assignment')
            ? 'The original bed was used after this discharge. It cannot be restored across that history, even if it is free now. Changing the cancellation time does not resolve this conflict.'
            : 'A later or overlapping hospital stay prevents this correction. Review the patient’s encounter history. Changing the cancellation time does not resolve this conflict.';
    }
    if (error.status === 409) {
        return error.message.startsWith('Bed is already occupied')
            ? 'The original bed is currently occupied. Cancellation must restore the original placement; a replacement bed cannot be selected here.'
            : 'The encounter or discharge record changed. Refresh the encounter and review the recorded discharge before saving.';
    }
    if (error.title === 'Invalid encounter location') return 'The original department, ward, or bed is no longer available for care. Refresh the encounter to review the placement.';
    return error.message;
}

element('discharge-cancellation-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (saving || !ready) return;
    Object.values(fields).forEach(clearFieldError);
    element('discharge-cancellation-error').hidden = true;
    const enteredTime = element('dischargeCancelledAt').value;
    const cancelledAt = new Date(enteredTime);
    const normalizedTime = enteredTime.length === 16 ? `${enteredTime}:00` : enteredTime;
    let timeError;
    if (Number.isNaN(cancelledAt.getTime()) || localDateTimeValue(cancelledAt, true) !== normalizedTime) {
        timeError = 'Enter a valid local cancellation time.';
    } else if (cancelledAt > new Date()) {
        timeError = 'Cancellation time cannot be in the future.';
    } else if (cancelledAt < new Date(discharge.dischargedAt)) {
        timeError = 'Cancellation time cannot be before discharge.';
    }
    if (timeError) {
        showFieldError('dischargeCancelledAt', timeError);
        element('dischargeCancelledAt').focus();
        return;
    }
    saving = true;
    updateControls();
    let saved;
    let firstErrorField;
    try {
        const latest = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/timeline`);
        const current = recordedDischarge(latest);
        if (!current || current.record.id !== discharge.id) {
            throw new ApiError(409, { detail: 'The discharge record has changed.' });
        }
        saved = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/discharge-cancellations`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ cancelledAt: cancelledAt.toISOString(), expectedDischargeId: discharge.id })
        });
    } catch (error) {
        let message = cancellationError(error);
        for (const [name, field] of Object.entries(fields)) {
            if (error.fields?.[name]) {
                showFieldError(field, error.fields[name]);
                firstErrorField ||= field;
                message = 'Check the highlighted fields and try again.';
            }
        }
        // A lost response may follow a committed correction. Recheck before another write.
        if (!(error instanceof ApiError) || error.status !== 400 || !firstErrorField) ready = false;
        element('discharge-cancellation-error').textContent = message;
        element('discharge-cancellation-error').hidden = false;
    } finally {
        saving = false;
        updateControls();
    }
    if (saved) {
        notice = `Discharge cancelled for ${saved.encounterNumber}. Care restored from ${formatEncounterTime(discharge.dischargedAt)}. The correction is recorded in the timeline.`;
        dialog.close();
    } else {
        element(firstErrorField || 'discharge-cancellation-error').focus();
    }
});
