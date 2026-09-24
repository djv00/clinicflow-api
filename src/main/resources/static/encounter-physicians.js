import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue,
    encounterStatuses, formatEncounterTime } from './workbench.js';
import { canWrite } from './account.js';

const dialog = element('responsibility-dialog');
const endReasons = { REASSIGNED: 'Handed over', RELEASED: 'Released',
    DEPARTMENT_TRANSFER: 'Department transfer', DISCHARGE: 'Discharged' };
let encounter, context, department, onClose;
let contextController, choicesController;
let ready = false, loading = false, saving = false, choicesLoading = false;
let mode = null, selectedPhysician = null;
let page = 0, totalPages = 0, keyword = '';

const currentAssignment = () => context?.assignments.find((item) => item.id === context.currentAssignmentId);
const physicianName = (item) => `${item.physicianFirstName} ${item.physicianLastName} (${item.physicianCode})`;
const canEdit = () => ready && canWrite() && context?.status === 'IN_DEPARTMENT' && context.currentLocation;
const url = () => `./api/v1/encounters/${encodeURIComponent(encounter.id)}/physician-assignments`;

function textNode(tag, value, className) {
    const node = document.createElement(tag);
    node.textContent = value;
    if (className) node.className = className;
    return node;
}

function updateControls() {
    element('responsibility-actions').hidden = !canEdit() || Boolean(mode);
    element('assign-responsibility').disabled = !canEdit() || !department?.active;
    element('assign-responsibility').textContent = currentAssignment() ? 'Hand over responsibility' : 'Assign physician';
    element('release-responsibility').hidden = !currentAssignment();
    element('responsibility-fields').disabled = !canEdit() || saving;
    element('save-responsibility').disabled = !canEdit() || saving || !mode
        || (mode === 'assign' && (!selectedPhysician || choicesLoading));
    element('save-responsibility').textContent = saving ? 'Saving…'
        : mode === 'release' ? 'Confirm release' : currentAssignment() ? 'Save handover' : 'Save assignment';
    element('refresh-responsibility').disabled = loading || saving;
    for (const id of ['close-responsibility', 'dismiss-responsibility', 'cancel-responsibility-change']) element(id).disabled = saving;
    for (const id of ['responsibility-keyword', 'search-responsibility', 'retry-responsibility-choices']) element(id).disabled = !canEdit() || saving;
    element('previous-responsibility').disabled = !canEdit() || saving || choicesLoading || page === 0;
    element('next-responsibility').disabled = !canEdit() || saving || choicesLoading || page + 1 >= totalPages;
    for (const input of element('responsibility-choices').querySelectorAll('input')) {
        input.disabled = !canEdit() || saving || input.value === currentAssignment()?.physicianId;
    }
    element('responsibility-form').setAttribute('aria-busy', String(saving));
    element('responsibility-content').setAttribute('aria-busy', String(loading));
}

function showError(message) {
    element('responsibility-error').textContent = message;
    element('responsibility-error').hidden = !message;
}

function resetEditor() {
    choicesController?.abort();
    choicesController = null;
    choicesLoading = false;
    mode = null;
    selectedPhysician = null;
    element('responsibility-editor').hidden = true;
    element('responsibility-choices').replaceChildren();
    clearFieldError('responsibility-time');
    updateControls();
}

function renderContext() {
    const current = currentAssignment();
    element('responsibility-content').hidden = false;
    element('responsibility-status').textContent = encounterStatuses[context.status] || context.status;
    element('responsibility-current').textContent = current ? physicianName(current)
        : context.status === 'IN_DEPARTMENT' ? 'No physician assigned' : 'No current responsibility';
    element('responsibility-since').textContent = formatEncounterTime(current?.startedAt);
    element('responsibility-department').textContent = context.currentLocation ? 'Loading…' : 'No current department';
    element('responsibility-guidance').textContent = context.status === 'IN_DEPARTMENT'
        ? 'One responsible physician at a time. Department transfers and discharge end responsibility. A cancelled discharge requires a new physician selection.'
        : context.status === 'ADMITTED' ? 'Enter a department before assigning a responsible physician.'
            : 'This encounter is closed. Its responsibility history is retained.';
    const rows = context.assignments.map((item) => {
        const row = document.createElement('tr');
        const doctor = textNode('td', physicianName(item));
        doctor.append(textNode('div', `${item.departmentName} (${item.departmentCode})`, 'history-note'));
        const from = textNode('td', formatEncounterTime(item.startedAt));
        from.append(textNode('div', `Assigned by ${item.assignedBy}`, 'history-note'));
        const until = textNode('td', formatEncounterTime(item.endedAt));
        if (item.endedBy) until.append(textNode('div', `Ended by ${item.endedBy}`, 'history-note'));
        row.append(doctor, from, until, textNode('td', item.id === context.currentAssignmentId
            ? 'Current' : endReasons[item.endReason] || item.endReason || 'Closed'));
        return row;
    });
    element('responsibility-history').replaceChildren(...rows);
    element('responsibility-history-empty').hidden = rows.length > 0;
    element('responsibility-history-region').hidden = rows.length === 0;
}

async function loadContext() {
    if (saving) return;
    contextController?.abort();
    const controller = new AbortController();
    contextController = controller;
    ready = false;
    loading = true;
    department = null;
    resetEditor();
    showError('');
    element('responsibility-content').hidden = true;
    element('responsibility-state').textContent = 'Loading responsibility and current department…';
    try {
        const result = await request(url(), {}, controller);
        if (controller !== contextController) return;
        context = result;
        renderContext();
        if (context.currentLocation) {
            const result = await request(`./api/v1/departments/${encodeURIComponent(context.currentLocation.departmentId)}`, {}, controller);
            if (controller !== contextController) return;
            department = result;
            element('responsibility-department').textContent = `${department.departmentName} (${department.departmentCode})${department.active ? '' : ' — inactive'}`;
            if (!department.active) element('responsibility-guidance').textContent = 'This department is inactive. Existing responsibility can be released; a new physician cannot be assigned here.';
        }
        ready = true;
        element('responsibility-state').textContent = canWrite() ? '' : 'Read-only access. An operator can change responsibility.';
    } catch {
        if (controller !== contextController) return;
        element('responsibility-state').textContent = '';
        if (context?.currentLocation) element('responsibility-department').textContent = 'Not verified';
        showError('Could not verify responsibility or the current department. Refresh responsibility before making a change.');
    } finally {
        if (controller === contextController) { loading = false; updateControls(); }
    }
}

function renderChange() {
    const previous = currentAssignment();
    element('responsibility-change').textContent = mode === 'release'
        ? `Release ${physicianName(previous)}. This leaves the encounter without a responsible physician; the patient stays in care.`
        : selectedPhysician
            ? `${previous ? `Hand over from ${physicianName(previous)} to` : 'Assign'} ${selectedPhysician.firstName} ${selectedPhysician.lastName} (${selectedPhysician.physicianCode}).${previous ? ' The previous responsibility ends at the same effective time.' : ''}`
            : 'Select an active physician affiliated with this department.';
}

async function loadChoices() {
    if (saving || mode !== 'assign' || !canEdit()) return;
    choicesController?.abort();
    const controller = new AbortController();
    choicesController = controller;
    selectedPhysician = null;
    choicesLoading = true;
    element('responsibility-choices').replaceChildren();
    element('responsibility-choices-state').textContent = 'Loading eligible physicians…';
    element('retry-responsibility-choices').hidden = true;
    renderChange();
    updateControls();
    try {
        const params = new URLSearchParams({ departmentId: context.currentLocation.departmentId,
            active: true, keyword, page, size: 5 });
        const result = await request(`./api/v1/physicians?${params}`, {}, controller);
        if (controller !== choicesController) return;
        page = result.page;
        totalPages = result.totalPages;
        for (const physician of result.items) {
            const label = document.createElement('label');
            label.className = 'responsibility-choice';
            const input = document.createElement('input');
            input.type = 'radio';
            input.name = 'responsible-physician';
            input.value = physician.id;
            const current = physician.id === currentAssignment()?.physicianId;
            input.addEventListener('change', () => { selectedPhysician = physician; renderChange(); updateControls(); });
            label.append(input, textNode('span', `${physician.firstName} ${physician.lastName} (${physician.physicianCode})${current ? ' — current physician' : ''}`));
            element('responsibility-choices').append(label);
        }
        element('responsibility-choices-state').textContent = result.totalElements
            ? `${result.totalElements} eligible physicians · Page ${result.totalPages ? page + 1 : 0} of ${totalPages}`
            : 'No matching active physicians in this department. A directory administrator can maintain affiliations.';
    } catch {
        if (controller !== choicesController) return;
        totalPages = 0;
        element('responsibility-choices-state').textContent = 'Could not load physicians. Retry the search.';
        element('retry-responsibility-choices').hidden = false;
    } finally {
        if (controller === choicesController) { choicesLoading = false; updateControls(); }
    }
}

function earliestTime() {
    return Math.max(new Date(context.currentLocation.startedAt).getTime(),
        ...context.assignments.map((item) => new Date(item.endedAt || item.startedAt).getTime()));
}

function beginChange(nextMode) {
    if (!canEdit() || saving || (nextMode === 'release' && !currentAssignment())
        || (nextMode === 'assign' && !department.active)) return;
    resetEditor();
    mode = nextMode;
    showError('');
    element('responsibility-notice').hidden = true;
    element('responsibility-editor').hidden = false;
    element('responsibility-picker').hidden = mode === 'release';
    element('responsibility-editor-title').textContent = mode === 'release' ? 'Release responsibility'
        : currentAssignment() ? 'Hand over responsibility' : 'Assign physician';
    element('responsibility-time').value = localDateTimeValue(new Date(), true);
    element('responsibility-time-hint').textContent = `Must be on or after ${formatEncounterTime(new Date(earliestTime()).toISOString())}, and not in the future.`;
    renderChange();
    updateControls();
    if (mode === 'assign') {
        page = 0;
        keyword = '';
        element('responsibility-keyword').value = '';
        loadChoices();
        element('responsibility-keyword').focus();
    } else element('responsibility-time').focus();
}

export function openEncounterPhysicians(selected, patient, afterClose) {
    encounter = selected;
    context = null;
    onClose = afterClose;
    element('responsibility-patient').textContent = patient;
    element('responsibility-encounter').textContent = selected.encounterNumber;
    element('responsibility-notice').hidden = true;
    element('responsibility-time-zone').textContent = `Times are local (${Intl.DateTimeFormat().resolvedOptions().timeZone}).`;
    dialog.showModal();
    loadContext();
}

element('assign-responsibility').addEventListener('click', () => beginChange('assign'));
element('release-responsibility').addEventListener('click', () => beginChange('release'));
element('cancel-responsibility-change').addEventListener('click', () => { if (!saving) resetEditor(); });
element('responsibility-time').addEventListener('input', () => clearFieldError('responsibility-time'));
element('refresh-responsibility').addEventListener('click', loadContext);
element('responsibility-search').addEventListener('submit', (event) => {
    event.preventDefault();
    if (saving) return;
    keyword = element('responsibility-keyword').value.trim();
    page = 0;
    loadChoices();
});
element('previous-responsibility').addEventListener('click', () => { if (!saving && !choicesLoading && page > 0) { page--; loadChoices(); } });
element('next-responsibility').addEventListener('click', () => { if (!saving && !choicesLoading && page + 1 < totalPages) { page++; loadChoices(); } });
element('retry-responsibility-choices').addEventListener('click', loadChoices);
for (const id of ['close-responsibility', 'dismiss-responsibility']) {
    element(id).addEventListener('click', () => { if (!saving) dialog.close(); });
}
dialog.addEventListener('cancel', (event) => { if (saving) event.preventDefault(); });
dialog.addEventListener('close', () => {
    contextController?.abort();
    contextController = null;
    resetEditor();
    context = null;
    encounter = null;
    onClose?.();
    onClose = null;
});

element('responsibility-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!canEdit() || saving || !mode || (mode === 'assign' && (!selectedPhysician || choicesLoading))) return;
    showError('');
    clearFieldError('responsibility-time');
    const entered = element('responsibility-time').value;
    const time = new Date(entered);
    const normalized = entered.length === 16 ? `${entered}:00` : entered;
    let timeError;
    if (Number.isNaN(time.getTime()) || localDateTimeValue(time, true) !== normalized) timeError = 'Enter a valid local effective time.';
    else if (time > new Date()) timeError = 'Effective time cannot be in the future.';
    else if (time.getTime() < earliestTime()) timeError = 'Effective time cannot precede the current placement or recorded responsibility.';
    if (timeError) {
        showFieldError('responsibility-time', timeError);
        element('responsibility-time').focus();
        return;
    }
    const releasing = mode === 'release';
    const body = releasing ? { expectedLocationId: context.currentLocation.id, endedAt: time.toISOString() }
        : { physicianId: selectedPhysician.id, expectedLocationId: context.currentLocation.id,
            expectedAssignmentId: context.currentAssignmentId, startedAt: time.toISOString() };
    const target = releasing ? `${url()}/${encodeURIComponent(context.currentAssignmentId)}/releases` : url();
    saving = true;
    updateControls();
    let saved = false;
    try {
        await request(target, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
        saved = true;
    } catch (error) {
        const fieldError = error.fields?.[releasing ? 'endedAt' : 'startedAt'];
        if (error instanceof ApiError && error.status === 400 && fieldError) {
            showFieldError('responsibility-time', fieldError);
            element('responsibility-time').focus();
        } else {
            // A lost response may follow a committed change. Never replay a write automatically.
            ready = false;
            showError(!(error instanceof ApiError) || error.status >= 500
                ? 'The change could not be confirmed. Refresh responsibility and check its history before making another change.'
                : error.status === 409 ? 'The encounter or responsibility changed. Refresh responsibility, review the current physician and department, then select the change again.'
                    : error.status === 403 ? 'This session cannot change responsibility. Refresh the page to check your access.'
                        : `${error.message} Refresh responsibility before making another change.`);
            element('responsibility-error').focus();
        }
    } finally {
        saving = false;
        updateControls();
    }
    if (saved) {
        element('responsibility-notice').textContent = releasing ? 'Responsibility released. The patient remains in care.' : 'Physician responsibility saved.';
        element('responsibility-notice').hidden = false;
        await loadContext();
    }
});
