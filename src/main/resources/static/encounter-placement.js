import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue,
    encounterStatuses, formatEncounterTime, loadPlacementDetails } from './workbench.js';

const fields = ['departmentId', 'wardId', 'bedId', 'startedAt'];
const dialog = element('department-dialog');
const workflows = {
    admission: { title: 'Enter department', label: 'Department entry', status: 'ADMITTED', path: 'department-admissions', timeField: 'startedAt' },
    transfer: { title: 'Transfer patient', label: 'Transfer', status: 'IN_DEPARTMENT', path: 'transfers', timeField: 'transferredAt' }
};
let workflow = workflows.admission;
let encounter;
let currentLocation;
let currentBed;
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
    element('save-department').textContent = saving ? 'Saving…' : `Save ${workflow.label.toLowerCase()}`;
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
        // The patient's own active bed can be retained when only the department changes.
        if (currentBed?.active && currentBed.wardId === wardId && !beds.some((bed) => bed.id === currentBed.id)) {
            beds.unshift(currentBed);
        }
        // Changing wards or refreshing availability must never retain a stale bed.
        const choices = [{ id: 'none', bedNumber: 'No bed assigned' }, ...beds];
        setOptions('bedId', 'Select a bed assignment', choices,
            (bed) => bed.id === 'none' ? bed.bedNumber
                : `Bed ${bed.bedNumber}${bed.id === currentBed?.id ? ' (current bed)' : ''}`, previous);
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

async function loadCurrentPlacement(location, controller) {
    const { department, ward, bed } = await loadPlacementDetails(location, controller);
    if (controller !== lookupController) return;
    currentBed = bed;
    const inactive = (value) => value.active ? '' : ' (inactive)';
    element('current-department').textContent = `${department.departmentName} (${department.departmentCode})${inactive(department)}`;
    element('current-ward').textContent = `${ward.wardName} (${ward.wardCode})${inactive(ward)}`;
    element('current-bed').textContent = bed ? `Bed ${bed.bedNumber}${inactive(bed)}` : 'No bed assigned';
    element('current-started-at').textContent = formatEncounterTime(location.startedAt);
}

async function loadPlacementOptions() {
    if (saving) return;
    lookupController?.abort();
    bedController?.abort();
    bedController = null;
    const controller = new AbortController();
    lookupController = controller;
    const previous = Object.fromEntries(fields.slice(0, 3).map((field) => [field, element(field).value]));
    const previousLocationId = currentLocation?.id;
    currentBed = null;
    fields.forEach(clearFieldError);
    element('department-error').hidden = true;
    lookupsReady = false;
    bedsReady = false;
    loading = true;
    showLookupState('Checking encounter and loading departments and wards…');
    updateControls();
    try {
        const [record, departments, wards] = await Promise.all([
            request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}${workflow === workflows.transfer ? '/timeline' : ''}`, {}, controller),
            request('./api/v1/departments?active=true', {}, controller),
            request('./api/v1/wards?active=true', {}, controller)
        ]);
        if (controller !== lookupController) return;
        const current = record.encounter || record;
        encounter = current;
        element('department-admitted-at').textContent = formatEncounterTime(current.admittedAt);
        if (current.status !== workflow.status) {
            showLookupState(`This encounter is now ${encounterStatuses[current.status] || current.status}. ${workflow.label} requires an encounter with status ${encounterStatuses[workflow.status]}. Close this form to refresh the patient record.`);
            return;
        }
        let locationChanged = false;
        if (workflow === workflows.transfer) {
            const openLocations = record.locations.filter((location) => location.endedAt === null);
            if (openLocations.length !== 1) {
                showLookupState('The current placement could not be established. Refresh availability before transferring.', true);
                return;
            }
            currentLocation = openLocations[0];
            await loadCurrentPlacement(currentLocation, controller);
            if (controller !== lookupController) return;
            locationChanged = !!previousLocationId && previousLocationId !== currentLocation.id;
            if (locationChanged) for (const field of fields.slice(0, 3)) previous[field] = '';
        }
        setOptions('departmentId', 'Select a department', departments,
            (department) => `${department.departmentName} (${department.departmentCode})`, previous.departmentId);
        setOptions('wardId', 'Select a ward', wards,
            (ward) => `${ward.wardName} (${ward.wardCode})`, previous.wardId);
        lookupsReady = departments.length > 0 && wards.length > 0;
        showLookupState(!lookupsReady ? 'No active departments or wards are available. Saving requires both.'
            : locationChanged ? 'The current placement has changed. Review it and choose a destination again before saving.' : '');
    } catch (error) {
        if (controller !== lookupController) return;
        showLookupState(error instanceof ApiError && error.status === 404
            ? 'The encounter or a location reference is no longer available. Close and reopen the patient record.'
            : 'Could not check the encounter or load locations. Use Refresh availability to try again.', true);
    } finally {
        if (controller === lookupController) {
            loading = false;
            updateControls();
        }
    }
    if (controller === lookupController && lookupsReady) await loadBeds(previous.bedId);
}

function openPlacement(selected, patient, afterClose, selectedWorkflow) {
    workflow = selectedWorkflow;
    encounter = selected;
    currentLocation = null;
    currentBed = null;
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
    element('placement-eyebrow').textContent = workflow.label;
    element('department-title').textContent = workflow.title;
    element('close-department').setAttribute('aria-label', `Close ${workflow.label.toLowerCase()}`);
    element('placement-legend').textContent = `${workflow.label} details`;
    element('placement-time-label').textContent = `${workflow.label} time`;
    element('startedAt').name = workflow.timeField;
    element('current-placement').hidden = workflow !== workflows.transfer;
    for (const id of ['current-department', 'current-ward', 'current-bed', 'current-started-at']) element(id).textContent = 'Loading…';
    element('destination-heading').hidden = workflow !== workflows.transfer;
    element('startedAt').value = localDateTimeValue(new Date(), true);
    element('department-time-hint').textContent = `Local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}). Must be on or after ${workflow === workflows.transfer ? 'the current placement start' : 'hospital admission'} and not in the future.`;
    element('bed-availability').textContent = 'Choose a ward to see available beds.';
    element('retry-beds').hidden = true;
    dialog.showModal();
    loadPlacementOptions();
}

export const openDepartmentAdmission = (selected, patient, afterClose) => openPlacement(selected, patient, afterClose, workflows.admission);
export const openEncounterTransfer = (selected, patient, afterClose) => openPlacement(selected, patient, afterClose, workflows.transfer);

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
        timeError = `Enter a valid local ${workflow.label.toLowerCase()} time.`;
    } else if (startedAt > new Date()) {
        timeError = `${workflow.label} time cannot be in the future.`;
    } else if (startedAt < new Date(currentLocation?.startedAt || encounter.admittedAt)) {
        timeError = `${workflow.label} time cannot be before ${currentLocation ? 'the current placement start' : 'hospital admission'}.`;
    }
    if (timeError) {
        showFieldError('startedAt', timeError);
        element('startedAt').focus();
        return;
    }
    const body = {
        departmentId: element('departmentId').value, wardId: element('wardId').value,
        bedId: element('bedId').value === 'none' ? null : element('bedId').value,
        [workflow.timeField]: startedAt.toISOString()
    };
    if (currentLocation && body.departmentId === currentLocation.departmentId
        && body.wardId === currentLocation.wardId && body.bedId === currentLocation.bedId) {
        element('department-error').textContent = 'Choose a different department, ward, or bed. The destination is the same as the current placement.';
        element('department-error').hidden = false;
        element('department-error').focus();
        return;
    }
    const department = element('departmentId').selectedOptions[0].textContent;
    const ward = element('wardId').selectedOptions[0].textContent;
    const bed = element('bedId').selectedOptions[0].textContent;
    saving = true;
    updateControls();
    let saved;
    let firstErrorField;
    try {
        if (currentLocation) {
            const latest = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/timeline`);
            const openLocations = latest.locations.filter((location) => location.endedAt === null);
            if (latest.encounter.status !== workflow.status || openLocations.length !== 1 || openLocations[0].id !== currentLocation.id) {
                throw new ApiError(409, { title: 'Placement changed', detail: 'The current placement changed. Refresh availability and review it before saving.' });
            }
        }
        saved = await request(`./api/v1/encounters/${encodeURIComponent(encounter.id)}/${workflow.path}`, {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
    } catch (error) {
        let message = error instanceof ApiError ? error.message
            : `${workflow.label} could not be confirmed. Refresh availability to check the encounter before trying again.`;
        if (!(error instanceof ApiError) || error.status >= 500) {
            message = `${workflow.label} could not be confirmed. Refresh availability to check the encounter before trying again.`;
        } else if (error.status === 409) {
            message = error.title === 'Encounter history conflict'
                ? 'The bed has occupancy history after the requested time. Check the time or choose another bed, then refresh availability.'
                : 'The encounter or placement has changed, or the bed is no longer available. Refresh availability before trying again.';
        } else if (error.status === 404 || error.status === 400 && !Object.keys(error.fields).length) {
            message = 'Check the time and refresh availability. The encounter or selected location may have changed.';
        }
        for (const field of fields) {
            const fieldError = error.fields?.[field === 'startedAt' ? workflow.timeField : field];
            if (fieldError) {
                showFieldError(field, fieldError);
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
        notice = `${workflow.label} saved for ${encounter.encounterNumber}: ${department} / ${ward} / ${bed}.`;
        dialog.close();
    } else {
        element(firstErrorField || 'department-error').focus();
    }
});
