package com.jiangyudai.clinicflow.encounter.exception;

import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;

public class InvalidEncounterStatusException extends RuntimeException {

    public InvalidEncounterStatusException(
            EncounterStatus currentStatus,
            EncounterStatus requiredStatus
    ) {
        super(
                "Encounter status must be "
                        + requiredStatus
                        + " but was "
                        + currentStatus
        );
    }
}