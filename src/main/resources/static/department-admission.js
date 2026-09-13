import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue,
    encounterStatuses, formatEncounterTime } from './workbench.js';

const fields = ['departmentId', 'wardId', 'bedId', 'startedAt'];
const dialog = element('department-dialog');
let encounter;
let onClose;
let notice;
let lookupController;
let bedController;
let lookupsReady = false;
let bedsReady = false;
let loading = false;
let saving = false;

function updateControls() {
    element('department-fields').disabled = !lookupsReady || saving;
    element('bedId').disabled = !bedsReady || saving;
    element('save-department').disabled = !lookupsReady || !bedsReady || saving;
    element('refresh-placement').disabled = loading || saving;
    element('retry-beds').disabled = saving;
    for (const id of ['close-department', 'cancel-department']) element(id).disabled = saving;
    element('department-form').setAttribute('aria-busy', String(loading || saving));
    element('save-department').textContent = saving ? 'Saving…' : 'Save department entry';
}

function setOptions(id, placeholder, entries, label, previous = '') {
    const select = element(id);
    select.replaceChildren(new Option(placeholder, ''));
    select.options[0].disabled = true;
    for (const entry of entries) select.add(new Option(label(entry), entry.id));
    select.value = entries.some((entry) => entry.id === previous) ? previous : '';
}

function showLookupState(message, error = false) {
    element('placement-state').textContent = message;
    element('placement-state').className = error ? 'message error' : 'message information';
    element('placement-state').hidden = !message;
}

async function loadBeds(previous = '') {
    bedController?.abort();
    const controller = new AbortController();
    bedController = controller;
    const wardId = element('wardId').value;
    bedsReady = false;
    clearFieldError('bedId');
    setOptions('bedId', 'Select a bed assignment', [], (bed) => bed.bedNumber);
    element('retry-beds').hidden = true;
    element('bed-availability').textContent = wardId ? 'Loading available beds…' : 'Choose a ward to see available beds.';
    updateControls();
    if (!wardId) return;
    try {
        const params = new URLSearchParams({ wardId, active: true, occupied: false });
        const beds = await request(`./api/v1/beds?${params}`, {}, controller);
        if (controller !== bedController) return;
        // Changing wards or refreshing availability must never retain a stale bed.
        const choices = [{ id: 'none', bedNumber: 'No bed assigned' }, ...beds];
        setOptions('bedId', 'Select a bed assignment', choices,
            (bed) => bed.id === 'none' ? bed.bedNumber : `Bed ${bed.bedNumber}`, previous);
        bedsReady = true;
        element('bed-availability').textContent = beds.length
            ? 'Availability is checked again when you save. You may also choose No bed assigned.'
            : 'No available beds in this ward. You can still choose No bed assigned.';
        if (previous && !choices.some((bed) => bed.id === previous)) {
            showFieldError('bedId', 'The selected bed is no longer available. Choose another assignment.');
        }
    } catch (error) {
        if (controller !== bedController) return;
        element('bed-availability').textContent = 'Could not load available beds. Retry before saving.';
        element('retry-beds').hidden = false;
    } finally {
        if (controller === bedController) updateControls();
    }
}

async function loadPlacementOptions() {
    if (saving) return;
    lookupController?.abort();
    bedController?.abort();
    bedController = null;
    const controller = new AbortController();
    lookupController = controller;
    const previous = Object.fromEntries(fields.slice(0, 3).map((field) => [field, element(field).value]));
    fields.forEach(clearFieldError);
    element('department-error').hidden = true;
    lookupsReady = false;
    bedsReady = false;
    loading = true;
    showLookupState('Checking encounter and loading departments and wards…');
    updateControls();
    try {
        const [current, departments, wards] = await Promise.all([
            request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}`, {}, controller),
            request('./api/v1/departments?active=true', {}, controller),
            request('./api/v1/wards?active=true', {}, controller)
        ]);
        if (controller !== lookupController) return;
        encounter = current;
        element('department-admitted-at').textContent = formatEncounterTime(current.admittedAt);
        if (current.status !== 'ADMITTED') {
            showLookupState(`This encounter is now ${encounterStatuses[current.status] || current.status}. Department entry requires an admitted encounter. Close this form to refresh the patient record.`);
            return;
        }
        setOptions('departmentId', 'Select a department', departments,
            (department) => `${department.departmentName} (${department.departmentCode})`, previous.departmentId);
        setOptions('wardId', 'Select a ward', wards,
            (ward) => `${ward.wardName} (${ward.wardCode})`, previous.wardId);
        lookupsReady = departments.length > 0 && wards.length > 0;
        showLookupState(lookupsReady ? '' : 'No active departments or wards are available. Department entry cannot be saved until both are available.');
    } catch (error) {
        if (controller !== lookupController) return;
        showLookupState(error instanceof ApiError && error.status === 404
            ? 'This encounter is no longer available. Close and reopen the patient record.'
            : 'Could not check the encounter or load locations. Use Refresh availability to try again.', true);
    } finally {
        if (controller === lookupController) {
            loading = false;
            updateControls();
        }
    }
    if (controller === lookupController && lookupsReady) await loadBeds(previous.bedId);
}

export function openDepartmentAdmission(selected, patient, afterClose) {
    encounter = selected;
    onClose = afterClose;
    notice = undefined;
    element('department-form').reset();
    setOptions('departmentId', 'Select a department', []);
    setOptions('wardId', 'Select a ward', []);
    setOptions('bedId', 'Select a bed assignment', []);
    fields.forEach(clearFieldError);
    element('department-error').hidden = true;
    element('department-patient').textContent = patient;
    element('department-encounter-number').textContent = selected.encounterNumber;
    element('department-admitted-at').textContent = formatEncounterTime(selected.admittedAt);
    element('startedAt').value = localDateTimeValue(new Date(), true);
    element('department-time-hint').textContent = `Local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}). Must be on or after hospital admission and not in the future.`;
    element('bed-availability').textContent = 'Choose a ward to see available beds.';
    element('retry-beds').hidden = true;
    dialog.showModal();
    loadPlacementOptions();
}

for (const id of ['close-department', 'cancel-department']) {
    element(id).addEventListener('click', () => { if (!saving) dialog.close(); });
}
dialog.addEventListener('cancel', (event) => { if (saving) event.preventDefault(); });
dialog.addEventListener('close', () => {
    lookupController?.abort();
    bedController?.abort();
    lookupController = null;
    bedController = null;
    encounter = null;
    onClose?.(notice);
    onClose = null;
});
fields.forEach((field) => element(field).addEventListener('input', () => clearFieldError(field)));
element('wardId').addEventListener('change', () => loadBeds());
element('refresh-placement').addEventListener('click', loadPlacementOptions);
element('retry-beds').addEventListener('click', () => loadBeds());

element('department-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (saving || !lookupsReady || !bedsReady) return;
    fields.forEach(clearFieldError);
    element('department-error').hidden = true;
    const enteredTime = element('startedAt').value;
    const startedAt = new Date(enteredTime);
    // datetime-local omits zero seconds when serializing its value.
    const normalizedTime = enteredTime.length === 16 ? `${enteredTime}:00` : enteredTime;
    let timeError;
    if (Number.isNaN(startedAt.getTime()) || localDateTimeValue(startedAt, true) !== normalizedTime) {
        timeError = 'Enter a valid local department entry time.';
    } else if (startedAt > new Date()) {
        timeError = 'Department entry time cannot be in the future.';
    } else if (startedAt < new Date(encounter.admittedAt)) {
        timeError = 'Department entry time cannot be before hospital admission.';
    }
    if (timeError) {
        showFieldError('startedAt', timeError);
        element('startedAt').focus();
        return;
    }
    const body = {
        departmentId: element('departmentId').value, wardId: element('wardId').value,
        bedId: element('bedId').value === 'none' ? null : element('bedId').value,
        startedAt: startedAt.toISOString()
    };
    const department = element('departmentId').selectedOptions[0].textContent;
    const ward = element('wardId').selectedOptions[0].textContent;
    const bed = element('bedId').selectedOptions[0].textContent;
    saving = true;
    updateControls();
    let saved;
    let firstErrorField;
    try {
        saved = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/department-admissions`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
    } catch (error) {
        let message = error instanceof ApiError ? error.message
            : 'Department entry could not be confirmed. Refresh availability to check the encounter before trying again.';
        if (!(error instanceof ApiError) || error.status >= 500) {
            message = 'Department entry could not be confirmed. Refresh availability to check the encounter before trying again.';
        } else if (error.status === 409) {
            message = error.title === 'Encounter history conflict'
                ? 'The bed has occupancy history after the requested entry time. Check the time or choose another bed, then refresh availability.'
                : 'The encounter has changed or the bed is no longer available. Refresh availability before trying again.';
        } else if (error.status === 404 || error.status === 400 && !Object.keys(error.fields).length) {
            message = 'Check the entry time and refresh availability. The encounter or selected location may have changed.';
        }
        for (const field of fields) {
            if (error.fields?.[field]) {
                showFieldError(field, error.fields[field]);
                firstErrorField ||= field;
                message = 'Check the highlighted fields and try again.';
            }
        }
        // A failed response can follow a committed write; recheck before another POST.
        if (!(error instanceof ApiError) || error.status !== 400 || !Object.keys(error.fields).length) lookupsReady = false;
        element('department-error').textContent = message;
        element('department-error').hidden = false;
    } finally {
        saving = false;
        updateControls();
    }
    if (saved) {
        notice = `Department entry saved for ${encounter.encounterNumber}: ${department} / ${ward} / ${bed}.`;
        dialog.close();
    } else {
        element(firstErrorField || 'department-error').focus();
    }
});
