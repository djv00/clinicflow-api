package com.jiangyudai.clinicflow.physician.exception;

import java.util.UUID;

public class PhysicianNotFoundException extends RuntimeException {
    public PhysicianNotFoundException(UUID id) {
        super("Physician not found: " + id);
    }
}
