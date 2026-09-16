import { element, ApiError, request, encounterStatuses, formatEncounterTime } from './workbench.js';

const dialog = element('timeline-dialog');
let encounterId;
let onClose;
let activeController;

function textNode(tag, text, className) {
    const node = document.createElement(tag);
    node.textContent = text;
    if (className) node.className = className;
    return node;
}

function timestamp(value) {
    if (!value) return textNode('span', '—');
    const time = textNode('time', formatEncounterTime(value));
    time.dateTime = value;
    time.title = value;
    return time;
}

function cell(...children) {
    const node = document.createElement('td');
    node.append(...children);
    return node;
}

function badge(label, current = false) {
    return textNode('span', label, `encounter-status${current ? ' active' : ''}`);
}

function locationNotes(location, discharges) {
    const notes = document.createElement('div');
    notes.append(badge(location.endedAt === null ? 'Current' : 'Closed', location.endedAt === null));
    const restoredBy = discharges.find((record) => record.cancelledAt && record.restoredLocationId === location.id);
    if (restoredBy) {
        notes.append(textNode('p', 'Care restored after discharge cancellation.', 'history-note'));
    }
    const discharge = discharges.find((record) => record.locationId === location.id);
    if (discharge) {
        notes.append(textNode('p', discharge.cancelledAt ? 'Discharge later cancelled.' : 'Ended at discharge.', 'history-note'));
    }
    return notes;
}

function renderHistory(timeline, references) {
    const { encounter, locations, discharges } = timeline;
    element('timeline-status').replaceChildren(badge(encounterStatuses[encounter.status] || encounter.status,
        ['ADMITTED', 'IN_DEPARTMENT'].includes(encounter.status)));
    element('timeline-admitted-at').replaceChildren(timestamp(encounter.admittedAt));
    element('timeline-ended-label').textContent = encounter.status === 'ADMISSION_CANCELLED' ? 'Admission cancelled' : 'Discharged';
    element('timeline-ended-at').replaceChildren(timestamp(encounter.status === 'ADMISSION_CANCELLED'
        ? encounter.admissionCancelledAt : encounter.dischargedAt));
    element('timeline-admission-cancellation').hidden = encounter.status !== 'ADMISSION_CANCELLED';
    element('timeline-admission-operator').textContent = encounter.admissionCancelledBy || 'Not recorded';

    const locationRows = document.createDocumentFragment();
    // Keep the API's stable order; equal timestamps do not establish operation order.
    for (const location of locations) {
        const row = document.createElement('tr');
        const placement = cell(textNode('div', references.get(`departments/${location.departmentId}`)));
        placement.append(textNode('div', references.get(`wards/${location.wardId}`), 'history-note'));
        row.append(cell(timestamp(location.startedAt)),
            cell(location.endedAt ? timestamp(location.endedAt) : textNode('span', 'Ongoing')),
            placement,
            cell(textNode('span', location.bedId ? references.get(`beds/${location.bedId}`) : 'No bed assigned')),
            cell(locationNotes(location, discharges)));
        locationRows.append(row);
    }
    element('timeline-location-rows').replaceChildren(locationRows);
    element('timeline-locations-empty').hidden = locations.length > 0;
    element('timeline-locations-table-region').hidden = locations.length === 0;

    const dischargeRows = document.createDocumentFragment();
    for (const discharge of discharges) {
        const row = document.createElement('tr');
        const location = locations.find((item) => item.id === discharge.locationId);
        const placement = location
            ? `${references.get(`departments/${location.departmentId}`)} / ${references.get(`wards/${location.wardId}`)} / ${location.bedId ? references.get(`beds/${location.bedId}`) : 'No bed assigned'}`
            : 'Location not available';
        const cancellation = cell();
        if (discharge.cancelledAt) {
            cancellation.append(timestamp(discharge.cancelledAt),
                textNode('div', `Recorded operator: ${discharge.cancelledBy || 'Not recorded'}`, 'history-note'));
            const restored = locations.find((item) => item.id === discharge.restoredLocationId);
            const continuation = textNode('div', 'Care continued from ', 'history-note');
            continuation.append(restored ? timestamp(restored.startedAt) : textNode('span', 'an unavailable location record'));
            cancellation.append(continuation);
        } else {
            cancellation.append(textNode('span', '—'));
        }
        row.append(cell(timestamp(discharge.dischargedAt)), cell(badge(discharge.cancelledAt ? 'Cancelled' : 'Recorded')),
            cell(textNode('span', placement)), cancellation);
        dischargeRows.append(row);
    }
    element('timeline-discharge-rows').replaceChildren(dischargeRows);
    element('timeline-discharges-empty').hidden = discharges.length > 0;
    element('timeline-discharges-table-region').hidden = discharges.length === 0;
    element('timeline-correction-note').hidden = !discharges.some((record) => record.cancelledAt);
}

async function loadTimeline() {
    activeController?.abort();
    const controller = new AbortController();
    activeController = controller;
    element('timeline-content').hidden = true;
    element('timeline-error').hidden = true;
    element('timeline-reference-warning').hidden = true;
    element('timeline-loading').hidden = false;
    element('refresh-timeline').disabled = true;
    dialog.setAttribute('aria-busy', 'true');
    try {
        const timeline = await request(`./api/v1/encounters/${encodeURIComponent(encounterId)}/timeline`, {}, controller);
        if (controller !== activeController) return;
        const queries = new Map();
        let unavailable = false;
        const loadReference = (type, id) => {
            const key = `${type}/${id}`;
            if (queries.has(key)) return;
            queries.set(key, request(`./api/v1/${type}/${encodeURIComponent(id)}`, {}, controller).then((value) => {
                const label = type === 'departments' ? `${value.departmentName} (${value.departmentCode})`
                    : type === 'wards' ? `${value.wardName} (${value.wardCode})` : `Bed ${value.bedNumber}`;
                return `${label}${value.active ? '' : ' (inactive)'}`;
            }).catch(() => {
                unavailable = true;
                return type === 'departments' ? 'Department unavailable' : type === 'wards' ? 'Ward unavailable' : 'Bed unavailable';
            }));
        };
        for (const location of timeline.locations) {
            loadReference('departments', location.departmentId);
            loadReference('wards', location.wardId);
            if (location.bedId) loadReference('beds', location.bedId);
        }
        const references = new Map(await Promise.all([...queries].map(async ([key, result]) => [key, await result])));
        if (controller !== activeController) return;
        renderHistory(timeline, references);
        element('timeline-reference-warning').hidden = !unavailable;
        element('timeline-content').hidden = false;
    } catch (error) {
        if (controller !== activeController) return;
        element('timeline-error').textContent = error instanceof ApiError && error.status === 404
            ? 'This encounter is no longer available. Close and reopen the patient record.'
            : 'Could not load the encounter timeline. Refresh the timeline to try again.';
        element('timeline-error').hidden = false;
    } finally {
        if (controller === activeController) {
            element('timeline-loading').hidden = true;
            element('refresh-timeline').disabled = false;
            dialog.setAttribute('aria-busy', 'false');
        }
    }
}

export function openEncounterTimeline(selected, patient, afterClose) {
    encounterId = selected.id;
    onClose = afterClose;
    element('timeline-patient').textContent = patient;
    element('timeline-encounter-number').textContent = selected.encounterNumber;
    element('timeline-time-zone').textContent = `Times are shown in your local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}).`;
    dialog.showModal();
    loadTimeline();
}

for (const id of ['close-timeline', 'done-timeline']) element(id).addEventListener('click', () => dialog.close());
element('refresh-timeline').addEventListener('click', loadTimeline);
dialog.addEventListener('close', () => {
    activeController?.abort();
    activeController = null;
    encounterId = null;
    onClose?.();
    onClose = null;
});
