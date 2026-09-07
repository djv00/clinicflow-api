package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class CurrentEncounterLocationNotFoundException
        extends RuntimeException {

    public CurrentEncounterLocationNotFoundException(UUID encounterId) {
        super(
                "Current encounter location not found for encounter: "
                        + encounterId
        );
    }
}
