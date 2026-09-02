package com.jiangyudai.clinicflow.patient.exception;

import java.util.UUID;

public class PatientNotFoundException extends RuntimeException {

    public PatientNotFoundException(UUID id) {
        super("Patient not found: " + id);
    }
}
