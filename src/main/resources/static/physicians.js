import { element, ApiError, request } from './workbench.js';
import { accountReady, canReadPhysicians, canManagePhysicians } from './account.js';

let filters = {};
let page = 0;
let totalPages = 0;
let listController;
let filterController;
let recordController;
let physicianId;
let current;
let departments = [];
let originalForm;
let loaded = false;
let loading = false;
let saving = false;
let reloadRequired = false;
let unconfirmedCode;
const dialog = element('physician-dialog');
const form = element('physician-form');

function textNode(tag, text, className) {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    return node;
}

function departmentLabel(department) {
    return `${department.departmentName} (${department.departmentCode})${department.active ? '' : ' — inactive'}`;
}

function renderRow(physician) {
    const row = document.createElement('tr');
    const affiliations = document.createElement('td');
    affiliations.append(...(physician.departments.length
        ? physician.departments.map((department) => textNode('div', departmentLabel(department)))
        : [textNode('span', 'Unassigned', 'muted')]));
    const availability = document.createElement('td');
    availability.append(textNode('span', physician.active ? 'Active' : 'Inactive',
        `encounter-status${physician.active ? ' active' : ''}`));
    const actions = document.createElement('td');
    const action = textNode('button', canManagePhysicians() ? 'Edit physician' : 'View details', 'row-action');
    action.type = 'button';
    action.setAttribute('aria-label', `${action.textContent}: ${physician.firstName} ${physician.lastName}, ${physician.physicianCode}`);
    action.addEventListener('click', () => openRecord(physician.id));
    actions.append(action);
    row.append(textNode('td', `${physician.lastName}, ${physician.firstName}`),
        textNode('td', physician.physicianCode, 'record-number'), affiliations, availability, actions);
    return row;
}

async function loadList() {
    listController?.abort();
    const controller = new AbortController();
    listController = controller;
    element('physician-rows').replaceChildren();
    element('physician-list-error').hidden = true;
    element('physician-list-state').hidden = false;
    element('physician-list-state').textContent = 'Loading physicians…';
    element('physician-summary').textContent = 'Loading physicians…';
    element('physician-page-summary').textContent = 'Page —';
    element('physician-table-region').setAttribute('aria-busy', 'true');
    element('previous-physicians').disabled = true;
    element('next-physicians').disabled = true;
    try {
        const params = new URLSearchParams({ ...filters, page, size: element('physician-page-size').value });
        const result = await request(`./api/v1/physicians?${params}`, {}, controller);
        if (listController !== controller) return;
        if (!result.items.length && page > 0) {
            page = Math.max(0, result.totalPages - 1);
            loadList();
            return;
        }
        page = result.page;
        totalPages = result.totalPages;
        element('physician-rows').replaceChildren(...result.items.map(renderRow));
        element('physician-list-state').hidden = result.items.length > 0;
        element('physician-list-state').textContent = Object.values(filters).some(Boolean)
            ? 'No physicians match these filters. Change or clear the filters to search again.'
            : 'No physicians registered yet.';
        const start = result.items.length ? page * result.size + 1 : 0;
        const end = result.items.length ? start + result.items.length - 1 : 0;
        element('physician-summary').textContent = `${start}–${end} of ${result.totalElements} physicians`;
        element('physician-page-summary').textContent = `Page ${totalPages ? page + 1 : 0} of ${totalPages}`;
        element('previous-physicians').disabled = page === 0;
        element('next-physicians').disabled = page + 1 >= totalPages;
    } catch (error) {
        if (listController !== controller) return;
        element('physician-summary').textContent = 'Physician list unavailable';
        element('physician-list-state').hidden = true;
        element('physician-list-error-text').textContent = error instanceof ApiError ? error.message
            : 'Could not load physicians. Check your connection and try again.';
        element('physician-list-error').hidden = false;
    } finally {
        if (listController === controller) element('physician-table-region').setAttribute('aria-busy', 'false');
    }
}

async function loadDepartmentFilter() {
    filterController?.abort();
    const controller = new AbortController();
    filterController = controller;
    element('retry-physician-filters').disabled = true;
    try {
        const values = await request('./api/v1/departments', {}, controller);
        if (controller !== filterController) return;
        const select = element('physician-department');
        const selected = select.value;
        const label = select.selectedOptions[0]?.textContent;
        select.replaceChildren(new Option('All departments', ''));
        for (const department of values) select.add(new Option(departmentLabel(department), department.id));
        if (selected && !values.some((value) => value.id === selected)) {
            select.add(new Option(label || 'Selected department unavailable', selected));
        }
        select.value = selected;
        select.disabled = false;
        element('physician-filter-error').hidden = true;
    } catch {
        if (controller === filterController) element('physician-filter-error').hidden = false;
    } finally {
        if (controller === filterController) element('retry-physician-filters').disabled = false;
    }
}

function profileInput() {
    return {
        firstName: element('physician-first-name').value.trim(),
        lastName: element('physician-last-name').value.trim(),
        departmentIds: [...element('physician-department-options').querySelectorAll('input:checked')]
            .map((input) => input.value).sort()
    };
}

function formValue() {
    return JSON.stringify({ physicianCode: element('physician-code').value.trim(), ...profileInput() });
}

function isDirty() { return loaded && originalForm !== formValue(); }

function refreshControls() {
    const busy = loading || saving;
    const confirming = !element('physician-active-confirm').hidden || !element('physician-discard-confirm').hidden;
    element('physician-fields').disabled = busy || !loaded || reloadRequired || confirming || !canManagePhysicians();
    element('save-physician').hidden = !canManagePhysicians();
    element('save-physician').disabled = busy || !loaded || reloadRequired || confirming;
    element('save-physician').textContent = saving ? 'Saving…' : physicianId ? 'Save changes' : 'Add physician';
    element('reload-physician').hidden = !physicianId && !unconfirmedCode && (loaded || loading);
    element('reload-physician').textContent = unconfirmedCode ? 'Check physician code'
        : physicianId ? isDirty() ? 'Discard edits and reload' : 'Reload record' : 'Retry departments';
    element('reload-physician').disabled = busy || confirming;
    element('change-physician-active').hidden = !current || !canManagePhysicians();
    element('change-physician-active').disabled = busy || reloadRequired || !loaded || isDirty() || confirming;
    element('physician-dirty-hint').hidden = !current || !isDirty() || !canManagePhysicians();
    for (const id of ['close-physician', 'done-physician', 'confirm-physician-active', 'cancel-physician-active',
        'discard-physician', 'keep-physician-editing']) element(id).disabled = saving;
    form.setAttribute('aria-busy', String(busy));
}

function showError(message) {
    element('physician-error').textContent = message;
    element('physician-error').hidden = false;
    element('physician-error').focus();
}

function closeConfirmations() {
    element('physician-active-confirm').hidden = true;
    element('change-physician-active').setAttribute('aria-expanded', 'false');
    element('physician-discard-confirm').hidden = true;
}

function fillProfile() {
    if (current) {
        element('physician-code').value = current.physicianCode;
        element('physician-first-name').value = current.firstName;
        element('physician-last-name').value = current.lastName;
    }
    element('physician-code').readOnly = Boolean(physicianId);
    element('physician-dialog-title').textContent = physicianId
        ? canManagePhysicians() ? 'Edit physician' : 'Physician details' : 'Add physician';
    element('physician-dialog-status').textContent = canManagePhysicians()
        ? 'Names are required. Select the departments this physician serves.' : 'This record is read-only for your account.';
    element('physician-departments-hint').textContent = canManagePhysicians()
        ? 'Select all departments this physician serves, or leave unassigned. Existing inactive affiliations may be retained or removed.'
        : 'Current service-department affiliations.';
    element('physician-availability').hidden = !current;
    element('physician-current-active').textContent = current?.active ? 'Active' : 'Inactive';
    element('change-physician-active').textContent = current?.active ? 'Deactivate' : 'Reactivate';
    const existing = new Set((current?.departments || []).map((department) => department.id));
    const choices = new Map(departments.map((department) => [department.id, department]));
    for (const department of current?.departments || []) {
        if (!choices.has(department.id)) choices.set(department.id, { ...department, active: false });
    }
    const nodes = [...choices.values()].sort((a, b) => a.departmentCode.localeCompare(b.departmentCode)).map((department) => {
        const label = document.createElement('label');
        label.className = 'department-option';
        const checkbox = document.createElement('input');
        checkbox.type = 'checkbox';
        checkbox.value = department.id;
        checkbox.checked = existing.has(department.id);
        checkbox.disabled = !department.active && !checkbox.checked;
        label.append(checkbox, textNode('span', departmentLabel(department)));
        return label;
    });
    element('physician-department-options').replaceChildren(...(nodes.length ? nodes
        : [textNode('p', canManagePhysicians() ? 'No departments available. You can save this physician unassigned.' : 'Unassigned', 'muted')]));
    originalForm = formValue();
}

async function loadRecord() {
    recordController?.abort();
    const controller = new AbortController();
    recordController = controller;
    loading = true;
    loaded = false;
    closeConfirmations();
    element('physician-error').hidden = true;
    element('physician-saved').hidden = true;
    element('physician-dialog-status').textContent = 'Loading physician details…';
    refreshControls();
    try {
        const [record, choices] = await Promise.all([
            physicianId ? request(`./api/v1/physicians/${encodeURIComponent(physicianId)}`, {}, controller) : null,
            canManagePhysicians() ? request('./api/v1/departments', {}, controller) : []
        ]);
        if (controller !== recordController) return;
        current = record;
        departments = canManagePhysicians() ? choices : record?.departments || [];
        fillProfile();
        loaded = true;
        reloadRequired = false;
    } catch (error) {
        if (controller !== recordController) return;
        element('physician-dialog-status').textContent = 'Record unavailable';
        showError(error instanceof ApiError ? error.message : 'Could not load the record and departments. Try again.');
    } finally {
        if (controller === recordController) {
            loading = false;
            refreshControls();
        }
    }
}

function openRecord(id = null) {
    if (dialog.open || (!id && !canManagePhysicians())) return;
    physicianId = id;
    current = null;
    unconfirmedCode = null;
    reloadRequired = false;
    loaded = false;
    form.reset();
    element('physician-availability').hidden = true;
    element('physician-department-options').replaceChildren();
    element('physician-dialog-title').textContent = id ? 'Physician details' : 'Add physician';
    dialog.showModal();
    loadRecord();
}

async function writeRecord(activeChange = false) {
    if (saving || loading || !loaded || reloadRequired || !canManagePhysicians()) return;
    if (activeChange && isDirty()) return;
    const creating = !physicianId;
    const code = element('physician-code').value.trim();
    const body = activeChange ? { active: !current.active, version: current.version }
        : creating ? { physicianCode: code, ...profileInput() } : { ...profileInput(), version: current.version };
    if (body.departmentIds?.length > 100) {
        showError('Select no more than 100 service departments.');
        return;
    }
    saving = true;
    element('physician-error').hidden = true;
    element('physician-saved').hidden = true;
    refreshControls();
    try {
        const path = creating ? '' : `/${encodeURIComponent(physicianId)}${activeChange ? '/active' : ''}`;
        current = await request(`./api/v1/physicians${path}`, {
            method: creating ? 'POST' : 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
        physicianId = current.id;
        fillProfile();
        element('physician-saved').textContent = activeChange
            ? `Physician ${current.active ? 'reactivated' : 'deactivated'}. Department affiliations are retained.`
            : creating ? 'Physician added.' : 'Changes saved.';
        element('physician-saved').hidden = false;
        loadList();
    } catch (error) {
        const conflict = !creating && error instanceof ApiError && error.status === 409;
        const uncertain = !(error instanceof ApiError) || error.status >= 500;
        reloadRequired = conflict || uncertain;
        if (uncertain && creating) unconfirmedCode = code;
        showError(conflict ? 'This physician was changed by another request. Reload the record and review the latest details before editing again. Your changes were not saved.'
            : uncertain ? creating
                ? 'Creation could not be confirmed. Check the physician code before trying again; the record may already have been saved.'
                : 'The save could not be confirmed. Reload the record to check what was saved before making further changes.'
            : error.message);
    } finally {
        saving = false;
        closeConfirmations();
        refreshControls();
    }
}

async function checkUnconfirmedCreation() {
    const controller = new AbortController();
    recordController?.abort();
    recordController = controller;
    loading = true;
    refreshControls();
    try {
        // A keyword match is not an exact-code lookup; inspect every result page before allowing a retry.
        for (let candidatePage = 0; ; candidatePage++) {
            const params = new URLSearchParams({ keyword: unconfirmedCode, page: candidatePage, size: 100 });
            const result = await request(`./api/v1/physicians?${params}`, {}, controller);
            if (controller !== recordController) return;
            const match = result.items.find((value) => value.physicianCode === unconfirmedCode);
            if (match) {
                physicianId = match.id;
                unconfirmedCode = null;
                await loadRecord();
                if (dialog.open && loaded) {
                    element('physician-saved').textContent = 'A record with this code exists. Review its details before making changes.';
                    element('physician-saved').hidden = false;
                }
                loadList();
                return;
            }
            if (candidatePage + 1 >= result.totalPages) break;
        }
        unconfirmedCode = null;
        reloadRequired = false;
        showError('No record with this exact code was found. Review the form before trying to add it again.');
    } catch (error) {
        if (controller === recordController) showError(error instanceof ApiError ? error.message
            : 'Could not check the physician code. Check your connection and try again.');
    } finally {
        if (controller === recordController) { loading = false; refreshControls(); }
    }
}

element('physician-search').addEventListener('submit', (event) => {
    event.preventDefault();
    filters = { keyword: element('physician-keyword').value.trim(), departmentId: element('physician-department').value,
        active: element('physician-active').value };
    page = 0;
    loadList();
});
element('clear-physicians').addEventListener('click', () => {
    element('physician-search').reset(); filters = {}; page = 0; loadList();
});
element('physician-page-size').addEventListener('change', () => { page = 0; loadList(); });
element('previous-physicians').addEventListener('click', () => { if (page > 0) { page--; loadList(); } });
element('next-physicians').addEventListener('click', () => { if (page + 1 < totalPages) { page++; loadList(); } });
for (const id of ['retry-physicians', 'refresh-physicians']) element(id).addEventListener('click', loadList);
element('retry-physician-filters').addEventListener('click', loadDepartmentFilter);
element('add-physician').addEventListener('click', () => openRecord());
form.addEventListener('input', () => { element('physician-saved').hidden = true; refreshControls(); });
form.addEventListener('submit', (event) => {
    event.preventDefault();
    if (!element('physician-active-confirm').hidden || !element('physician-discard-confirm').hidden) return;
    for (const id of ['physician-code', 'physician-first-name', 'physician-last-name']) element(id).value = element(id).value.trim();
    if (form.reportValidity()) writeRecord();
});
element('reload-physician').addEventListener('click', () => {
    if (!loading && !saving) unconfirmedCode ? checkUnconfirmedCreation() : loadRecord();
});
element('change-physician-active').addEventListener('click', () => {
    if (!current || saving || loading || reloadRequired || isDirty() || !canManagePhysicians()) return;
    element('physician-active-explanation').textContent = current.active
        ? 'Deactivate this physician? Their profile and department affiliations will remain available.'
        : 'Reactivate this physician with their existing department affiliations?';
    element('confirm-physician-active').textContent = current.active ? 'Confirm deactivation' : 'Confirm reactivation';
    element('physician-active-confirm').hidden = false;
    element('change-physician-active').setAttribute('aria-expanded', 'true');
    refreshControls();
    element('confirm-physician-active').focus();
});
element('confirm-physician-active').addEventListener('click', () => writeRecord(true));
element('cancel-physician-active').addEventListener('click', () => { closeConfirmations(); refreshControls(); });
function closeRecord() {
    if (saving) return;
    if (isDirty() && canManagePhysicians()) {
        closeConfirmations();
        element('physician-discard-confirm').hidden = false;
        refreshControls();
        element('keep-physician-editing').focus();
    } else dialog.close();
}
for (const id of ['close-physician', 'done-physician']) element(id).addEventListener('click', closeRecord);
dialog.addEventListener('cancel', (event) => { event.preventDefault(); closeRecord(); });
element('discard-physician').addEventListener('click', () => { if (!saving) dialog.close(); });
element('keep-physician-editing').addEventListener('click', () => { closeConfirmations(); refreshControls(); });
dialog.addEventListener('close', () => { recordController?.abort(); recordController = null; });

await accountReady;
if (canReadPhysicians()) {
    element('physicians-view').hidden = false;
    element('add-physician').hidden = !canManagePhysicians();
    element('physician-read-only').hidden = canManagePhysicians();
    loadList();
    loadDepartmentFilter();
}
