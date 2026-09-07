package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class SameEncounterLocationException
        extends RuntimeException {

    public SameEncounterLocationException(UUID encounterId) {
        super(
                "Encounter is already assigned to the requested location: "
                        + encounterId
        );
    }
}