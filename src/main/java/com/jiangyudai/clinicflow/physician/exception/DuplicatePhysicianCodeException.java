package com.jiangyudai.clinicflow.physician.exception;

public class DuplicatePhysicianCodeException extends RuntimeException {
    public DuplicatePhysicianCodeException(String code) {
        super("Physician code already exists: " + code);
    }
}
