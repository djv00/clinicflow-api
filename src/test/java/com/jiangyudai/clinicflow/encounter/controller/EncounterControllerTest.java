package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterExistsException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EncounterController.class)
class EncounterControllerTest {

    private static final UUID PATIENT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );

    private static final OffsetDateTime ADMITTED_AT =
            OffsetDateTime.parse("2025-09-02T16:30:00-04:00");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EncounterService encounterService;

    @Test
    void admitsPatient() throws Exception {
        Encounter encounter = createEncounter();

        when(encounterService.admitPatient(
                eq(PATIENT_ID),
                eq("ENC-2026-000001"),
                any(OffsetDateTime.class)
        )).thenReturn(encounter);

        mockMvc.perform(post("/api/v1/encounters")
                        .contentType("application/json")
                        .content("""
                                {
                                  "patientId": "11111111-1111-1111-1111-111111111111",
                                  "encounterNumber": "ENC-2026-000001",
                                  "admittedAt": "2025-09-02T16:30:00-04:00"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.encounterNumber")
                        .value("ENC-2026-000001"))
                .andExpect(jsonPath("$.patientId")
                        .value(PATIENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.admittedAt")
                        .value("2025-09-02T16:30:00-04:00"));
    }

    @Test
    void returnsEncounter() throws Exception {
        Encounter encounter = createEncounter();

        when(encounterService.getEncounter(ENCOUNTER_ID))
                .thenReturn(encounter);

        mockMvc.perform(get("/api/v1/encounters/{id}", ENCOUNTER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encounterNumber")
                        .value("ENC-2026-000001"))
                .andExpect(jsonPath("$.status").value("ADMITTED"));
    }

    @Test
    void rejectsMissingPatientId() throws Exception {
        mockMvc.perform(post("/api/v1/encounters")
                        .contentType("application/json")
                        .content("""
                                {
                                  "encounterNumber": "ENC-2026-000001",
                                  "admittedAt": "2025-09-02T16:30:00-04:00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.patientId")
                        .value("Patient ID is required"));
    }

    @Test
    void rejectsFutureAdmissionTime() throws Exception {
        mockMvc.perform(post("/api/v1/encounters")
                        .contentType("application/json")
                        .content("""
                                {
                                  "patientId": "11111111-1111-1111-1111-111111111111",
                                  "encounterNumber": "ENC-2026-000001",
                                  "admittedAt": "2099-09-02T16:30:00-04:00"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.admittedAt")
                        .value("Admission time cannot be in the future"));
    }

    @Test
    void rejectsSecondActiveEncounter() throws Exception {
        when(encounterService.admitPatient(
                eq(PATIENT_ID),
                eq("ENC-2026-000001"),
                any(OffsetDateTime.class)
        )).thenThrow(new ActiveEncounterExistsException(PATIENT_ID));

        mockMvc.perform(post("/api/v1/encounters")
                        .contentType("application/json")
                        .content("""
                                {
                                  "patientId": "11111111-1111-1111-1111-111111111111",
                                  "encounterNumber": "ENC-2026-000001",
                                  "admittedAt": "2025-09-02T16:30:00-04:00"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Active encounter already exists"));
    }

    @Test
    void returnsNotFoundForMissingEncounter() throws Exception {
        when(encounterService.getEncounter(ENCOUNTER_ID))
                .thenThrow(new EncounterNotFoundException(ENCOUNTER_ID));

        mockMvc.perform(get("/api/v1/encounters/{id}", ENCOUNTER_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title")
                        .value("Encounter not found"));
    }

    private Encounter createEncounter() {
        Patient patient = mock(Patient.class);
        when(patient.getId()).thenReturn(PATIENT_ID);

        return new Encounter(
                "ENC-2026-000001",
                patient,
                ADMITTED_AT
        );
    }
}
