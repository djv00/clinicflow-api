package com.jiangyudai.clinicflow.encounter.exception;

import java.util.UUID;

public class BedHistoryConflictException extends RuntimeException {

    public BedHistoryConflictException(UUID bedId) {
        super("Bed assignment overlaps existing bed history: " + bedId);
    }
}
