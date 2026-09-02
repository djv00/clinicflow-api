package com.jiangyudai.clinicflow.encounter.exception;

public class DuplicateEncounterNumberException extends RuntimeException {

    public DuplicateEncounterNumberException(String encounterNumber) {
        super("Encounter number already exists: " + encounterNumber);
    }
}