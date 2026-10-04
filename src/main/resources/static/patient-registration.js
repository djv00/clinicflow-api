import { element, ApiError, request, clearFieldError, showFieldError } from './workbench.js';
import { canWrite } from './account.js';

export function initializePatientRegistration({ onRegistered }) {
    const registrationFields = ['medicalRecordNumber', 'firstName', 'lastName', 'dateOfBirth'];
    let saving = false;

    element('register-patient').addEventListener('click', () => {
        if (!canWrite()) return;
        element('registration-form').reset();
        registrationFields.forEach(clearFieldError);
        element('registration-error').hidden = true;
        // The server checks birth dates in the hospital time zone; the browser may be elsewhere.
        element('registration-dialog').showModal();
        element('medicalRecordNumber').focus();
    });
    for (const id of ['close-registration', 'cancel-registration']) {
        element(id).addEventListener('click', () => { if (!saving) element('registration-dialog').close(); });
    }
    element('registration-dialog').addEventListener('cancel', (event) => { if (saving) event.preventDefault(); });
    registrationFields.forEach((field) => element(field).addEventListener('input', () => clearFieldError(field)));

    element('registration-form').addEventListener('submit', async (event) => {
        event.preventDefault();
        if (saving || !canWrite()) return;
        registrationFields.forEach(clearFieldError);
        const body = Object.fromEntries(registrationFields.map((field) => [field, element(field).value.trim()]));
        saving = true;
        element('registration-fields').disabled = true;
        for (const id of ['save-patient', 'close-registration', 'cancel-registration']) element(id).disabled = true;
        element('save-patient').textContent = 'Saving…';
        element('registration-error').hidden = true;
        let patient;
        let firstErrorField;
        try {
            patient = await request('./api/v1/patients', {
                method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
            });
        } catch (error) {
            let message = error instanceof ApiError ? error.message
                : 'Could not confirm registration. Search for this medical record number before trying again.';
            if (error instanceof ApiError && error.status >= 500) {
                message = 'Could not confirm registration. Search for this medical record number before trying again.';
            }
            if (error instanceof ApiError && error.code === 'DUPLICATE_MEDICAL_RECORD_NUMBER') {
                showFieldError('medicalRecordNumber', 'This medical record number is already registered.');
                firstErrorField = 'medicalRecordNumber';
                message = 'A patient with this medical record number already exists. Cancel and search for the existing record.';
            }
            for (const field of registrationFields) {
                if (error.fields?.[field]) {
                    showFieldError(field, error.fields[field]);
                    firstErrorField ||= field;
                    message = 'Check the highlighted fields and try again.';
                }
            }
            element('registration-error').textContent = message;
            element('registration-error').hidden = false;
        } finally {
            saving = false;
            element('registration-fields').disabled = false;
            for (const id of ['save-patient', 'close-registration', 'cancel-registration']) element(id).disabled = false;
            element('save-patient').textContent = 'Save patient';
        }
        if (firstErrorField) element(firstErrorField).focus();
        if (patient) {
            element('registration-dialog').close();
            element('notice').textContent = `Registered ${patient.firstName} ${patient.lastName} (${patient.medicalRecordNumber}). The directory is now filtered by this record number.`;
            element('notice').hidden = false;
            onRegistered(patient);
        }
    });
}
