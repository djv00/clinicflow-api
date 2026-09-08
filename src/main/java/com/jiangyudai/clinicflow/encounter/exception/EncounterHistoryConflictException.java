package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class EncounterHistoryConflictException extends RuntimeException {

    public EncounterHistoryConflictException(UUID patientId) {
        super("Admission cannot precede an existing discharge for patient: " + patientId);
    }
}
