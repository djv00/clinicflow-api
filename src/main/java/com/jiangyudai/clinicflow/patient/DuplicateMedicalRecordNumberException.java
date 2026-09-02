package com.jiangyudai.clinicflow.patient;

public class DuplicateMedicalRecordNumberException extends RuntimeException {

    public DuplicateMedicalRecordNumberException(String medicalRecordNumber) {
        super("Medical record number already exists: " + medicalRecordNumber);
    }
}