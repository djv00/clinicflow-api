package com.jiangyudai.clinicflow.patient.controller;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.exception.DuplicateMedicalRecordNumberException;
import com.jiangyudai.clinicflow.patient.exception.PatientNotFoundException;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PatientController.class)
class PatientControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PatientService patientService;

    @Test
    void createsPatient() throws Exception {
        Patient patient = new Patient(
                "MRN-100001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        );

        when(patientService.createPatient(
                "MRN-100001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        )).thenReturn(patient);

        mockMvc.perform(post("/api/v1/patients")
                        .contentType("application/json")
                        .content("""
                                {
                                  "medicalRecordNumber": "MRN-100001",
                                  "firstName": "Maya",
                                  "lastName": "Chen",
                                  "dateOfBirth": "1990-05-14"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.medicalRecordNumber")
                        .value("MRN-100001"));
    }

    @Test
    void rejectsDuplicateMedicalRecordNumber() throws Exception {
        when(patientService.createPatient(
                "MRN-100001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        )).thenThrow(new DuplicateMedicalRecordNumberException("MRN-100001"));

        mockMvc.perform(post("/api/v1/patients")
                        .contentType("application/json")
                        .content("""
                                {
                                  "medicalRecordNumber": "MRN-100001",
                                  "firstName": "Maya",
                                  "lastName": "Chen",
                                  "dateOfBirth": "1990-05-14"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Duplicate medical record number"));
    }

    @Test
    void rejectsFutureDateOfBirth() throws Exception {
        mockMvc.perform(post("/api/v1/patients")
                        .contentType("application/json")
                        .content("""
                                {
                                  "medicalRecordNumber": "MRN-100002",
                                  "firstName": "Future",
                                  "lastName": "Patient",
                                  "dateOfBirth": "2099-01-01"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dateOfBirth")
                        .value("Date of birth must be in the past"));
    }

    @Test
    void returnsNotFoundForMissingPatient() throws Exception {
        UUID patientId = UUID.fromString(
                "11111111-1111-1111-1111-111111111111"
        );

        when(patientService.getPatient(patientId))
                .thenThrow(new PatientNotFoundException(patientId));

        mockMvc.perform(get("/api/v1/patients/{id}", patientId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Patient not found"));
    }
}
