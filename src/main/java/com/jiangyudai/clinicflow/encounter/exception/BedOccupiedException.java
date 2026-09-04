package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class BedOccupiedException extends RuntimeException {

    public BedOccupiedException(UUID bedId) {
        super("Bed is already occupied: " + bedId);
    }
}