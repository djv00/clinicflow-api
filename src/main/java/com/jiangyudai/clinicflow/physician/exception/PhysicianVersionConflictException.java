package com.jiangyudai.clinicflow.physician.exception;

public class PhysicianVersionConflictException extends RuntimeException {
    public PhysicianVersionConflictException() {
        super("The physician record has changed. Reload it before saving.");
    }
}
