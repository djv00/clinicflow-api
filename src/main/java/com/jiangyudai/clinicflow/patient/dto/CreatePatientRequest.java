package com.jiangyudai.clinicflow.patient.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreatePatientRequest(

        @NotBlank(message = "Medical record number is required")
        @Size(max = 50, message = "Medical record number is too long")
        String medicalRecordNumber,

        @NotBlank(message = "First name is required")
        @Size(max = 100, message = "First name is too long")
        String firstName,

        @NotBlank(message = "Last name is required")
        @Size(max = 100, message = "Last name is too long")
        String lastName,

        @NotNull(message = "Date of birth is required")
        @Past(message = "Date of birth must be in the past")
        LocalDate dateOfBirth
) {
}
