package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class EncounterNotFoundException extends RuntimeException {

    public EncounterNotFoundException(UUID id) {
        super("Encounter not found: " + id);
    }
}