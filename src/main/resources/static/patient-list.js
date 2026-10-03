import { element, ApiError, request, formatBirthDate } from './workbench.js';
import { accountReady, canReadClinical } from './account.js';

export function createPatientList({ onOpenPatient }) {
    let keyword = '';
    let page = 0;
    let totalPages = 0;
    let listVersion = 0;
    let listController;

    function setListState(title, detail = '') {
        element('list-state-title').textContent = title;
        element('list-state-detail').textContent = detail;
        element('list-state').hidden = false;
    }

    async function loadPatients() {
        await accountReady;
        if (!canReadClinical()) return;
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
                for (const value of [name, patient.medicalRecordNumber, formatBirthDate(patient.dateOfBirth)]) {
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
                view.addEventListener('click', () => onOpenPatient(patient.id));
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

    function filterByRecordNumber(recordNumber) {
        keyword = recordNumber;
        element('keyword').value = keyword;
        page = 0;
        return loadPatients();
    }

    return { load: loadPatients, filterByRecordNumber };
}
