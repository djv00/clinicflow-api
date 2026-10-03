import { element, ApiError, request, clearFieldError, showFieldError, localDateTimeValue } from './workbench.js';
import { canWrite } from './account.js';

export function createPatientAdmission({ getPatientId, onSaved, onRefresh }) {
    const admissionFields = ['encounterNumber', 'admittedAt'];
    let admitting = false;

    function hideAdmissionForm() {
        element('admission-form').hidden = true;
        element('admit-patient').hidden = !canWrite();
        element('admit-patient').setAttribute('aria-expanded', 'false');
    }

    element('admit-patient').addEventListener('click', () => {
        if (!canWrite()) return;
        element('admission-form').reset();
        admissionFields.forEach(clearFieldError);
        element('admission-error').hidden = true;
        element('admission-notice').hidden = true;
        element('refresh-admission-records').hidden = true;
        element('admittedAt').value = localDateTimeValue(new Date());
        element('admission-time-hint').textContent = `Local time (${Intl.DateTimeFormat().resolvedOptions().timeZone}). Future times are not allowed.`;
        element('admission-form').hidden = false;
        element('admit-patient').setAttribute('aria-expanded', 'true');
        element('admit-patient').hidden = true;
        element('encounterNumber').focus();
    });
    element('cancel-admission').addEventListener('click', () => {
        if (admitting) return;
        hideAdmissionForm();
        element('admit-patient').focus();
    });
    admissionFields.forEach((field) => element(field).addEventListener('input', () => clearFieldError(field)));
    element('refresh-admission-records').addEventListener('click', () => {
        onRefresh();
    });

    element('admission-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        if (admitting || !canWrite()) return;
        admissionFields.forEach(clearFieldError);
        element('admission-error').hidden = true;
        element('refresh-admission-records').hidden = true;
        const encounterNumber = element('encounterNumber').value.trim();
        const enteredTime = element('admittedAt').value;
        const admittedAt = new Date(enteredTime);
        if (!encounterNumber) {
            showFieldError('encounterNumber', 'Encounter number is required.');
            element('encounterNumber').focus();
            return;
        }
        // Reject local times that the clock skips when daylight saving time starts.
        if (Number.isNaN(admittedAt.getTime()) || localDateTimeValue(admittedAt) !== enteredTime) {
            showFieldError('admittedAt', 'Enter a valid local admission time.');
            element('admittedAt').focus();
            return;
        }
        if (admittedAt > new Date()) {
            showFieldError('admittedAt', 'Admission time cannot be in the future.');
            element('admittedAt').focus();
            return;
        }
        admitting = true;
        element('admission-fields').disabled = true;
        element('admission-form').setAttribute('aria-busy', 'true');
        const buttons = ['save-admission', 'cancel-admission', 'close-patient', 'done-patient'];
        buttons.forEach((id) => { element(id).disabled = true; });
        element('save-admission').textContent = 'Saving…';
        let encounter;
        let firstErrorField;
        try {
            encounter = await request('./api/v1/encounters', {
                method: 'POST', headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ patientId: getPatientId(), encounterNumber, admittedAt: admittedAt.toISOString() })
            });
        } catch (error) {
            const uncertain = !(error instanceof ApiError) || error.status >= 500;
            let message = uncertain
                ? 'Admission could not be confirmed. Refresh encounters and check this encounter number before trying again.'
                : error.message;
            element('refresh-admission-records').hidden = !(uncertain || error.status === 409);
            const fieldErrors = { ...error.fields };
            if (error.status === 409 && error.code === 'DUPLICATE_ENCOUNTER_NUMBER') {
                fieldErrors.encounterNumber = 'This encounter number is already in use. Check the existing record or use a new number.';
            } else if (error.status === 409 && error.code === 'ACTIVE_ENCOUNTER_EXISTS') {
                message = 'This patient already has an active hospital encounter. Review the existing encounter before admitting again.';
            } else if (error.status === 409 && error.code === 'ENCOUNTER_HISTORY_CONFLICT') {
                fieldErrors.admittedAt = 'Admission time cannot be before a previous discharge for this patient.';
            } else if (error.status === 400 && error.code === 'INVALID_ADMISSION_TIME') {
                fieldErrors.admittedAt = 'Admission time cannot be in the future.';
            } else if (error.status === 404) {
                message = 'This patient record is no longer available. Close and reopen the patient record.';
            }
            for (const field of admissionFields) {
                if (fieldErrors[field]) {
                    showFieldError(field, fieldErrors[field]);
                    firstErrorField ||= field;
                    message = 'Check the highlighted fields and try again.';
                }
            }
            element('admission-error').textContent = message;
            element('admission-error').hidden = false;
        } finally {
            admitting = false;
            element('admission-fields').disabled = false;
            element('admission-form').setAttribute('aria-busy', 'false');
            buttons.forEach((id) => { element(id).disabled = false; });
            element('save-admission').textContent = 'Save admission';
        }
        if (encounter) {
            hideAdmissionForm();
            element('admission-notice').textContent = `Admission saved: ${encounter.encounterNumber}. Status: Admitted.`;
            element('admission-notice').hidden = false;
            element('admission-notice').focus();
            onSaved();
        } else {
            element(firstErrorField || 'admission-error').focus();
        }
    });

    return { hide: hideAdmissionForm, isSaving: () => admitting };
}
