package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class EncounterLocationHistoryExistsException extends RuntimeException {

    public EncounterLocationHistoryExistsException(UUID encounterId) {
        super("Cannot cancel an admission with location history: " + encounterId);
    }
}
