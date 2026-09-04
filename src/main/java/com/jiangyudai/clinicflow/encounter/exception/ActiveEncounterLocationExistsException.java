package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class ActiveEncounterLocationExistsException
        extends RuntimeException {

    public ActiveEncounterLocationExistsException(UUID encounterId) {
        super("Encounter already has a current location: " + encounterId);
    }
}