package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class SubsequentEncounterExistsException extends RuntimeException {

    public SubsequentEncounterExistsException(UUID patientId) {
        super("A later or overlapping encounter prevents discharge cancellation for patient: " + patientId);
    }
}
