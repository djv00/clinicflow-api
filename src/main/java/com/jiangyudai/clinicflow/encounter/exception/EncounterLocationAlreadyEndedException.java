package com.jiangyudai.clinicflow.encounter.exception;

public class EncounterLocationAlreadyEndedException
        extends RuntimeException {

    public EncounterLocationAlreadyEndedException() {
        super("Encounter location is already ended");
    }
}