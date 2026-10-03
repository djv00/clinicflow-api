package com.jiangyudai.clinicflow.persistence;

import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser(roles = "OPERATOR")
class BusinessNumberIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private PatientService patients;
    @Autowired
    private EncounterService encounters;

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void patientApiNormalizesBeforeCheckingDuplicates(boolean paddedFirst) throws Exception {
        String number = "MRN-" + UUID.randomUUID();
        String body = """
                {"medicalRecordNumber":"%s","firstName":"Maya","lastName":"Chen","dateOfBirth":"1990-05-14"}
                """;
        mvc.perform(post("/api/v1/patients").with(csrf()).contentType("application/json")
                        .content(body.formatted(paddedFirst ? " " + number + " " : number)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.medicalRecordNumber").value(number));
        mvc.perform(post("/api/v1/patients").with(csrf()).contentType("application/json")
                        .content(body.formatted(paddedFirst ? number : " " + number + " ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate medical record number"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void admissionApiNormalizesAcrossDifferentPatients(boolean paddedFirst) throws Exception {
        UUID first = registerPatient();
        UUID second = registerPatient();
        String number = "ENC-" + UUID.randomUUID();
        String body = """
                {"patientId":"%s","encounterNumber":"%s","admittedAt":"2025-09-01T09:00:00Z"}
                """;
        mvc.perform(post("/api/v1/encounters").with(csrf()).contentType("application/json")
                        .content(body.formatted(first, paddedFirst ? " " + number + " " : number)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.encounterNumber").value(number));
        mvc.perform(post("/api/v1/encounters").with(csrf()).contentType("application/json")
                        .content(body.formatted(second, paddedFirst ? number : " " + number + " ")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Duplicate encounter number"));
    }

    @Test
    void normalizationPreservesCaseAndInternalSpaces() {
        String prefix = UUID.randomUUID().toString().substring(0, 12);
        var patient = patients.createPatient("\t " + prefix + "-a B \r\n", "Maya", "Chen", LocalDate.of(1990, 5, 14));
        assertThat(patient.medicalRecordNumber()).isEqualTo(prefix + "-a B");
        var encounter = encounters.admitPatient(patient.id(), "\t " + prefix + "-c D \r\n", OffsetDateTime.now().minusDays(1));
        assertThat(encounter.encounterNumber()).isEqualTo(prefix + "-c D");
        assertThat(patients.createPatient(prefix + "-A B", "Alex", "Martin", LocalDate.of(1985, 1, 1)).id())
                .isNotEqualTo(patient.id());
    }

    private UUID registerPatient() {
        return patients.createPatient("MRN-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 1, 1)).id();
    }
}
