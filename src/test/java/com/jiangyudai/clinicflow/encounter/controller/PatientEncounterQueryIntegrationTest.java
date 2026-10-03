package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.patient.dto.PatientResponse;
import com.jiangyudai.clinicflow.encounter.dto.EncounterResponse;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:patient-encounters-it;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@Transactional
@WithMockUser(username = "test-operator", roles = "OPERATOR")
class PatientEncounterQueryIntegrationTest {

    @Autowired
    private EncounterLocationRepository locations;

    private static final OffsetDateTime ADMITTED_AT = OffsetDateTime.parse("2025-09-01T09:00:00-04:00");
    private static final String PATH = "/api/v1/patients/{patientId}/encounters";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PatientService patientService;
    @Autowired
    private EncounterService encounterService;
    @Autowired
    private EntityManager entityManager;

    private PatientResponse patient;
    private EncounterResponse current;
    private Department department;
    private Ward ward;

    @BeforeEach
    void setUp() {
        patient = patientService.createPatient("HISTORY-001", "Maya", "Chen", LocalDate.of(1990, 5, 14));
        department = new Department("MED", "Medicine");
        ward = new Ward("WARD-A", "Ward A");
        entityManager.persist(department);
        entityManager.persist(ward);

        EncounterResponse cancelled = encounterService.admitPatient(patient.id(), "VISIT-001", ADMITTED_AT.minusDays(2));
        encounterService.cancelAdmission(cancelled.id(), ADMITTED_AT.minusDays(2).plusHours(1), "test-clerk");
        EncounterResponse discharged = encounterService.admitPatient(patient.id(), "VISIT-002", ADMITTED_AT.minusDays(1));
        encounterService.admitToDepartment(discharged.id(), department.getId(), ward.getId(), null,
                ADMITTED_AT.minusDays(1).plusHours(1));
        encounterService.dischargeEncounter(discharged.id(), ADMITTED_AT.minusDays(1).plusHours(2), "test-clerk", currentLocationId(discharged.id()));
        current = encounterService.admitPatient(patient.id(), "VISIT-003", ADMITTED_AT);

        PatientResponse other = patientService.createPatient("HISTORY-002", "Maya", "Chen", LocalDate.of(1980, 6, 15));
        encounterService.admitPatient(other.id(), "OTHER-PATIENT", ADMITTED_AT.plusHours(1));
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void listsOnlyThePatientsEncountersIncludingCancelledAdmissions() throws Exception {
        mockMvc.perform(get(PATH, patient.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[*].patientId", everyItem(is(patient.id().toString()))))
                .andExpect(jsonPath("$.items[0].id").value(current.id().toString()))
                .andExpect(jsonPath("$.items[0].status").value("ADMITTED"))
                .andExpect(jsonPath("$.items[0].dischargedAt").isEmpty())
                .andExpect(jsonPath("$.items[1].encounterNumber").value("VISIT-002"))
                .andExpect(jsonPath("$.items[1].status").value("DISCHARGED"))
                .andExpect(jsonPath("$.items[1].dischargedAt").isNotEmpty())
                .andExpect(jsonPath("$.items[2].encounterNumber").value("VISIT-001"))
                .andExpect(jsonPath("$.items[2].status").value("ADMISSION_CANCELLED"))
                .andExpect(jsonPath("$.items[2].admissionCancelledAt").isNotEmpty())
                .andExpect(jsonPath("$.items[2].admissionCancelledBy").value("test-clerk"));
    }

    @Test
    void returnsSeparatePagesAndKeepsTotalsBeyondTheLastPage() throws Exception {
        mockMvc.perform(get(PATH, patient.id()).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].encounterNumber").value("VISIT-003"))
                .andExpect(jsonPath("$.items[1].encounterNumber").value("VISIT-002"))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get(PATH, patient.id()).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].encounterNumber").value("VISIT-001"))
                .andExpect(jsonPath("$.page").value(1));
        mockMvc.perform(get(PATH, patient.id()).param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void returnsAnEmptyPageForAPatientWithNoEncounters() throws Exception {
        PatientResponse empty = patientService.createPatient("HISTORY-003", "Noah", "Adams", LocalDate.of(1980, 1, 1));
        mockMvc.perform(get(PATH, empty.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @Test
    void reportsAnUnknownPatientInsteadOfAnEmptyHistory() throws Exception {
        mockMvc.perform(get(PATH, UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Patient not found"));
    }

    @Test
    void rejectsAnInvalidPatientId() throws Exception {
        mockMvc.perform(get(PATH, "not-a-uuid")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101", "page,abc", "size,abc", "page,2147483648"})
    void rejectsInvalidPagination(String parameter, String value) throws Exception {
        mockMvc.perform(get(PATH, patient.id()).param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + parameter).exists());
    }

    @Test
    void rejectsAnOffsetOutsideJpaRange() throws Exception {
        mockMvc.perform(get(PATH, patient.id()).param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageOffsetValid").exists());
    }

    @Test
    void usesEncounterNumberToBreakAdmissionTimeTies() throws Exception {
        encounterService.cancelAdmission(current.id(), ADMITTED_AT.plusHours(1), "test-clerk");
        EncounterResponse replacement = encounterService.admitPatient(patient.id(), "VISIT-004", ADMITTED_AT);
        mockMvc.perform(get(PATH, patient.id()).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(replacement.id().toString()))
                .andExpect(jsonPath("$.totalElements").value(4));
        mockMvc.perform(get(PATH, patient.id()).param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(current.id().toString()));
    }

    @Test
    void reflectsDepartmentEntryDischargeAndDischargeCancellation() throws Exception {
        encounterService.admitToDepartment(current.id(), department.getId(), ward.getId(), null, ADMITTED_AT.plusHours(1));
        mockMvc.perform(get(PATH, patient.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("IN_DEPARTMENT"));
        encounterService.dischargeEncounter(current.id(), ADMITTED_AT.plusHours(2), "test-clerk", currentLocationId(current.id()));
        mockMvc.perform(get(PATH, patient.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("DISCHARGED"))
                .andExpect(jsonPath("$.items[0].dischargedAt").isNotEmpty());
        encounterService.cancelDischarge(current.id(), ADMITTED_AT.plusHours(3), "test-clerk");
        mockMvc.perform(get(PATH, patient.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("IN_DEPARTMENT"))
                .andExpect(jsonPath("$.items[0].dischargedAt").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    private UUID currentLocationId(UUID encounterId) {
        return locations.findByEncounter_IdAndEndedAtIsNull(encounterId)
                .map(location -> location.getId()).orElse(null);
    }
}
