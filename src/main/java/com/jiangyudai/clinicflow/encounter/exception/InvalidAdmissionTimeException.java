package com.jiangyudai.clinicflow.encounter.exception;

import java.time.OffsetDateTime;

public class InvalidAdmissionTimeException extends RuntimeException {

    public InvalidAdmissionTimeException(OffsetDateTime admittedAt) {
        super("Admission time cannot be in the future: " + admittedAt);
    }
}