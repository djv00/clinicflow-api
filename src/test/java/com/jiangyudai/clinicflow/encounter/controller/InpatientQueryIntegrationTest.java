package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.location.entity.Bed;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:inpatients-it;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@Transactional
class InpatientQueryIntegrationTest {
    private static final String PATH = "/api/v1/inpatients";
    private static final OffsetDateTime ADMITTED_AT = OffsetDateTime.parse("2025-09-01T09:00:00-04:00");

    @Autowired private MockMvc mockMvc;
    @Autowired private PatientService patientService;
    @Autowired private EncounterService encounterService;
    @Autowired private EntityManager entityManager;

    private Encounter waiting;
    private Encounter placed;
    private Department medicine;
    private Department rehabilitation;
    private Ward firstWard;
    private Ward secondWard;

    @BeforeEach
    void setUp() {
        medicine = new Department("MED", "Medicine");
        rehabilitation = new Department("REHAB", "Rehabilitation");
        firstWard = new Ward("WARD-A", "Ward A");
        secondWard = new Ward("WARD-B", "Ward B");
        entityManager.persist(medicine);
        entityManager.persist(rehabilitation);
        entityManager.persist(firstWard);
        entityManager.persist(secondWard);
        Bed bed = new Bed("01", firstWard);
        entityManager.persist(bed);
        waiting = admit("STAY-001", "MRN-001", "Maya", "Chen");
        placed = admit("STAY-002", "MRN-002", "Evan", "Cole");
        encounterService.admitToDepartment(placed.getId(), medicine.getId(), firstWard.getId(), bed.getId(), ADMITTED_AT.plusHours(1));
        encounterService.transferEncounter(placed.getId(), rehabilitation.getId(), secondWard.getId(), null, ADMITTED_AT.plusHours(2));
        Encounter discharged = admit("STAY-003", "MRN-003", "Theo", "Gray");
        encounterService.admitToDepartment(discharged.getId(), medicine.getId(), firstWard.getId(), bed.getId(), ADMITTED_AT.plusHours(2));
        encounterService.dischargeEncounter(discharged.getId(), ADMITTED_AT.plusHours(3));
        Encounter cancelled = admit("STAY-004", "MRN-004", "Lena", "Ross");
        encounterService.cancelAdmission(cancelled.getId(), ADMITTED_AT.plusMinutes(30), "test-clerk");
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void includesOnlyCurrentStaysWithTheirCurrentPlacement() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.items[0].id").value(waiting.getId().toString()))
                .andExpect(jsonPath("$.items[0].patientId").value(waiting.getPatient().getId().toString()))
                .andExpect(jsonPath("$.items[0].medicalRecordNumber").value("MRN-001"))
                .andExpect(jsonPath("$.items[0].dateOfBirth").value("1990-05-14"))
                .andExpect(jsonPath("$.items[0].departmentId").isEmpty())
                .andExpect(jsonPath("$.items[1].status").value("IN_DEPARTMENT"))
                .andExpect(jsonPath("$.items[1].departmentCode").value("REHAB"))
                .andExpect(jsonPath("$.items[1].wardName").value("Ward B"))
                .andExpect(jsonPath("$.items[1].bedId").isEmpty());
    }

    @Test
    void returnsTheAssignedBedForCurrentDepartmentCare() throws Exception {
        Bed bed = new Bed("02", entityManager.find(Ward.class, firstWard.getId()));
        entityManager.persist(bed);
        encounterService.admitToDepartment(waiting.getId(), medicine.getId(), firstWard.getId(), bed.getId(), ADMITTED_AT.plusHours(1));
        mockMvc.perform(get(PATH).param("status", "IN_DEPARTMENT").param("wardId", firstWard.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].bedId").value(bed.getId().toString()))
                .andExpect(jsonPath("$.items[0].bedNumber").value("02"));
    }

    @Test
    void filtersCurrentLocationsRatherThanPastPlacements() throws Exception {
        mockMvc.perform(get(PATH).param("departmentId", medicine.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(PATH).param("wardId", firstWard.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        mockMvc.perform(get(PATH).param("departmentId", rehabilitation.getId().toString())
                        .param("wardId", secondWard.getId().toString()).param("status", "IN_DEPARTMENT").param("keyword", "cole"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(placed.getId().toString()))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(get(PATH).param("status", "ADMITTED").param("wardId", secondWard.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void paginatesWithStableTimeTiesAndCorrectTotals() throws Exception {
        mockMvc.perform(get(PATH).param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].encounterNumber").value("STAY-001"))
                .andExpect(jsonPath("$.totalElements").value(2)).andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get(PATH).param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].encounterNumber").value("STAY-002"));
        mockMvc.perform(get(PATH).param("size", "1").param("page", "2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(2)).andExpect(jsonPath("$.totalElements").value(2));
    }

    @ParameterizedTest
    @CsvSource({"maya chen,STAY-001", "MRN-002,STAY-002", "stay-002,STAY-002"})
    void searchesNamesAndIdentifiersIgnoringCase(String keyword, String encounterNumber) throws Exception {
        mockMvc.perform(get(PATH).param("keyword", "  " + keyword + "  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].encounterNumber").value(encounterNumber));
    }

    @Test
    void treatsSearchMetacharactersLiterally() throws Exception {
        admit("STAY-%_!", "MRN-SPECIAL", "Nora", "Hill");
        mockMvc.perform(get(PATH).param("keyword", "%_!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].encounterNumber").value("STAY-%_!"));
    }

    @Test
    void includesCurrentCareEvenIfItsLocationHasBeenDeactivated() throws Exception {
        entityManager.createQuery("update Department d set d.active = false where d.id = :id")
                .setParameter("id", rehabilitation.getId()).executeUpdate();
        entityManager.createQuery("update Ward w set w.active = false where w.id = :id")
                .setParameter("id", secondWard.getId()).executeUpdate();
        mockMvc.perform(get(PATH).param("departmentId", rehabilitation.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].departmentName").value("Rehabilitation"))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void reflectsEntryDischargeAndCancellationWithoutDuplicatingHistoricalLocations() throws Exception {
        encounterService.admitToDepartment(waiting.getId(), medicine.getId(), firstWard.getId(), null, ADMITTED_AT.plusHours(1));
        encounterService.dischargeEncounter(placed.getId(), ADMITTED_AT.plusHours(4));
        mockMvc.perform(get(PATH).param("status", "IN_DEPARTMENT"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        encounterService.cancelDischarge(placed.getId(), ADMITTED_AT.plusHours(5), "test-clerk");
        mockMvc.perform(get(PATH).param("status", "IN_DEPARTMENT").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get(PATH).param("wardId", secondWard.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(placed.getId().toString()));
    }

    @Test
    void returnsEmptyForUnknownLocationsAndUnmatchedSearches() throws Exception {
        mockMvc.perform(get(PATH).param("wardId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mockMvc.perform(get(PATH).param("keyword", "not-registered"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101", "page,abc", "page,2147483648",
            "departmentId,invalid", "wardId,invalid", "status,invalid", "status,DISCHARGED", "status,ADMISSION_CANCELLED"})
    void rejectsInvalidFiltersAndPagination(String parameter, String value) throws Exception {
        mockMvc.perform(get(PATH).param(parameter, value)).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedKeywordsAndPageOffsets() throws Exception {
        mockMvc.perform(get(PATH).param("keyword", "x".repeat(101))).andExpect(status().isBadRequest());
        mockMvc.perform(get(PATH).param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest());
    }

    private Encounter admit(String encounterNumber, String mrn, String firstName, String lastName) {
        Patient patient = patientService.createPatient(mrn, firstName, lastName, LocalDate.of(1990, 5, 14));
        return encounterService.admitPatient(patient.getId(), encounterNumber, ADMITTED_AT);
    }
}
