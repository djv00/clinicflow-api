import { createPatientList } from './patient-list.js';
import { createPatientRecord } from './patient-record.js';
import { initializePatientRegistration } from './patient-registration.js';

// Compose the patient workflow once; list and form modules do not import each other.
const record = createPatientRecord();
const list = createPatientList({ onOpenPatient: record.open });
initializePatientRegistration({ onRegistered: patient => list.filterByRecordNumber(patient.medicalRecordNumber) });

export const loadPatients = list.load;
export const openPatient = record.open;

loadPatients();
