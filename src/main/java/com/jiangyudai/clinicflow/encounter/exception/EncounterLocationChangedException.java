package com.jiangyudai.clinicflow.encounter.exception;

public class EncounterLocationChangedException extends RuntimeException {
    public EncounterLocationChangedException() {
        super("The current placement has changed. Reload it before saving.");
    }
}
