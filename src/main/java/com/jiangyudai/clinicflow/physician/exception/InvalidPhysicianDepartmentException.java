package com.jiangyudai.clinicflow.physician.exception;

import java.util.UUID;

public class InvalidPhysicianDepartmentException extends RuntimeException {
    public InvalidPhysicianDepartmentException(UUID id) {
        super("Cannot add an inactive department: " + id);
    }
}
