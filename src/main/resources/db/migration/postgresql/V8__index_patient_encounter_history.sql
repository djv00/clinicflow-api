CREATE INDEX idx_encounters_patient_history
    ON encounters (patient_id, admitted_at DESC, encounter_number DESC);
