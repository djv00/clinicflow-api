package com.jiangyudai.clinicflow.patient.dto;

import com.jiangyudai.clinicflow.patient.entity.Patient;

import java.time.LocalDate;
import java.util.UUID;

public record PatientResponse(
        UUID id,
        String medicalRecordNumber,
        String firstName,
        String lastName,
        LocalDate dateOfBirth
) {

    public static PatientResponse from(Patient patient) {
        return new PatientResponse(
                patient.getId(),
                patient.getMedicalRecordNumber(),
                patient.getFirstName(),
                patient.getLastName(),
                patient.getDateOfBirth()
        );
    }
}
