import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue,
    encounterStatuses, formatEncounterTime, loadPlacementDetails } from './workbench.js';

const dialog = element('discharge-dialog');
let encounter;
let currentLocation;
let onClose;
let notice;
let contextController;
let ready = false;
let loading = false;
let saving = false;

function updateControls() {
    element('discharge-fields').disabled = !ready || saving;
    element('save-discharge').disabled = !ready || saving;
    element('refresh-discharge').disabled = loading || saving;
    for (const id of ['close-discharge', 'cancel-discharge']) element(id).disabled = saving;
    element('save-discharge').textContent = saving ? 'Saving…' : 'Save discharge';
    element('discharge-form').setAttribute('aria-busy', String(loading || saving));
}

function showState(message, error = false) {
    element('discharge-state').textContent = message;
    element('discharge-state').className = error ? 'message error' : 'message information';
    element('discharge-state').hidden = !message;
}

async function loadContext() {
    if (saving) return;
    contextController?.abort();
    const controller = new AbortController();
    contextController = controller;
    ready = false;
    loading = true;
    clearFieldError('dischargedAt');
    element('discharge-error').hidden = true;
    showState('Checking encounter and current placement…');
    updateControls();
    try {
        const timeline = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/timeline`, {}, controller);
        if (controller !== contextController) return;
        encounter = timeline.encounter;
        element('discharge-admitted-at').textContent = formatEncounterTime(encounter.admittedAt);
        if (encounter.status !== 'IN_DEPARTMENT') {
            showState(encounter.status === 'DISCHARGED'
                ? `This encounter was discharged at ${formatEncounterTime(encounter.dischargedAt)}. Close this form to refresh the patient record.`
                : `This encounter is now ${encounterStatuses[encounter.status] || encounter.status}. Discharge requires an encounter that is in a department. Close this form to refresh the patient record.`);
            element('discharge-placement').hidden = true;
            return;
        }
        const openLocations = timeline.locations.filter((location) => location.endedAt === null);
        if (openLocations.length !== 1) {
            showState('The current placement could not be established. Refresh the encounter before discharging.', true);
            return;
        }
        const location = openLocations[0];
        const { department, ward, bed } = await loadPlacementDetails(location, controller);
        if (controller !== contextController) return;
        const changed = currentLocation && currentLocation.id !== location.id;
        currentLocation = location;
        const inactive = (value) => value.active ? '' : ' (inactive)';
        element('discharge-department').textContent = `${department.departmentName} (${department.departmentCode})${inactive(department)}`;
        element('discharge-ward').textContent = `${ward.wardName} (${ward.wardCode})${inactive(ward)}`;
        element('discharge-bed').textContent = bed ? `Bed ${bed.bedNumber}${inactive(bed)}` : 'No bed assigned';
        element('discharge-started-at').textContent = formatEncounterTime(location.startedAt);
        element('discharge-placement').hidden = false;
        ready = true;
        showState(changed ? 'The current placement has changed. Review the new placement and discharge time before saving.' : '');
    } catch (error) {
        if (controller !== contextController) return;
        showState(error instanceof ApiError && error.status === 404
            ? 'The encounter or a location reference is no longer available. Close and reopen the patient record.'
            : 'Could not check the encounter or current placement. Use Refresh encounter to try again.', true);
    } finally {
        if (controller === contextController) {
            loading = false;
            updateControls();
        }
    }
}

export function openEncounterDischarge(selected, patient, afterClose) {
    encounter = selected;
    currentLocation = null;
    onClose = afterClose;
    notice = undefined;
    element('discharge-form').reset();
    clearFieldError('dischargedAt');
    element('discharge-error').hidden = true;
    element('discharge-patient').textContent = patient;
    element('discharge-encounter-number').textContent = selected.encounterNumber;
    element('discharge-admitted-at').textContent = formatEncounterTime(selected.admittedAt);
    element('discharge-placement').hidden = false;
    for (const id of ['discharge-department', 'discharge-ward', 'discharge-bed', 'discharge-started-at']) element(id).textContent = 'Loading…';
    element('dischargedAt').value = localDateTimeValue(new Date(), true);
    element('discharge-time-hint').textContent = `Local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}). Must be on or after the current placement start and not in the future.`;
    dialog.showModal();
    loadContext();
}

for (const id of ['close-discharge', 'cancel-discharge']) {
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
element('dischargedAt').addEventListener('input', () => clearFieldError('dischargedAt'));
element('refresh-discharge').addEventListener('click', loadContext);

element('discharge-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (saving || !ready) return;
    clearFieldError('dischargedAt');
    element('discharge-error').hidden = true;
    const enteredTime = element('dischargedAt').value;
    const dischargedAt = new Date(enteredTime);
    const normalizedTime = enteredTime.length === 16 ? `${enteredTime}:00` : enteredTime;
    let timeError;
    if (Number.isNaN(dischargedAt.getTime()) || localDateTimeValue(dischargedAt, true) !== normalizedTime) {
        timeError = 'Enter a valid local discharge time.';
    } else if (dischargedAt > new Date()) {
        timeError = 'Discharge time cannot be in the future.';
    } else if (dischargedAt < new Date(currentLocation.startedAt) || dischargedAt < new Date(encounter.admittedAt)) {
        timeError = 'Discharge time cannot be before hospital admission or the current placement start.';
    }
    if (timeError) {
        showFieldError('dischargedAt', timeError);
        element('dischargedAt').focus();
        return;
    }
    saving = true;
    updateControls();
    let saved;
    let fieldError;
    try {
        const latest = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/timeline`);
        const openLocations = latest.locations.filter((location) => location.endedAt === null);
        if (latest.encounter.status !== 'IN_DEPARTMENT' || openLocations.length !== 1 || openLocations[0].id !== currentLocation.id) {
            throw new ApiError(409, { detail: 'The encounter or current placement changed. Refresh the encounter and review it before saving.' });
        }
        saved = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/discharges`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ dischargedAt: dischargedAt.toISOString() })
        });
    } catch (error) {
        let message = error instanceof ApiError ? error.message
            : 'Discharge could not be confirmed. Refresh the encounter to check its status before trying again.';
        if (!(error instanceof ApiError) || error.status >= 500) {
            message = 'Discharge could not be confirmed. Refresh the encounter to check its status before trying again.';
        } else if (error.status === 409) {
            message = 'The encounter or current placement changed. Refresh the encounter and review it before saving.';
        } else if (error.status === 404) {
            message = 'The encounter is no longer available. Close and reopen the patient record.';
        }
        fieldError = error.fields?.dischargedAt;
        if (fieldError) {
            showFieldError('dischargedAt', fieldError);
            message = 'Check the discharge time and try again.';
        }
        // The server may have committed despite a lost response. Recheck before retrying.
        if (!(error instanceof ApiError) || error.status !== 400 || !fieldError) ready = false;
        element('discharge-error').textContent = message;
        element('discharge-error').hidden = false;
    } finally {
        saving = false;
        updateControls();
    }
    if (saved) {
        notice = `Discharge saved for ${saved.encounterNumber} at ${formatEncounterTime(saved.dischargedAt)}. Status: Discharged.`;
        dialog.close();
    } else {
        element(fieldError ? 'dischargedAt' : 'discharge-error').focus();
    }
});
