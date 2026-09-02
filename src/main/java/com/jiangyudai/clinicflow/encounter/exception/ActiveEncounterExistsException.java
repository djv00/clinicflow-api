package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class ActiveEncounterExistsException extends RuntimeException {

    public ActiveEncounterExistsException(UUID patientId) {
        super("Patient already has an active encounter: " + patientId);
    }
}