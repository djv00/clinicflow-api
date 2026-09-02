package com.jiangyudai.clinicflow.patient.controller;

import com.jiangyudai.clinicflow.patient.dto.CreatePatientRequest;
import com.jiangyudai.clinicflow.patient.dto.PatientResponse;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService patientService;

    public PatientController(PatientService patientService) {
        this.patientService = patientService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponse createPatient(
            @Valid @RequestBody CreatePatientRequest request
    ) {
        Patient patient = patientService.createPatient(
                request.medicalRecordNumber(),
                request.firstName(),
                request.lastName(),
                request.dateOfBirth()
        );

        return PatientResponse.from(patient);
    }

    @GetMapping("/{id}")
    public PatientResponse getPatient(@PathVariable UUID id) {
        return PatientResponse.from(patientService.getPatient(id));
    }
}
