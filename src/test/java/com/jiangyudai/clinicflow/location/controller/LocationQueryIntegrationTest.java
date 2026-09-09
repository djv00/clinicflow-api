package com.jiangyudai.clinicflow.location.controller;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.exception.BedHistoryConflictException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.exception.InvalidLocationException;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:location-query-it;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
@Transactional
class LocationQueryIntegrationTest {

    private static final OffsetDateTime ADMITTED_AT = OffsetDateTime.parse("2025-09-01T08:00:00-04:00");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private EncounterService encounterService;
    @PersistenceContext
    private EntityManager entityManager;

    private Department department;
    private Department inactiveDepartment;
    private Ward firstWard;
    private Ward secondWard;
    private Ward inactiveWard;
    private Bed firstBed;
    private Bed inactiveBed;
    private Bed secondBed;
    private Bed bedInInactiveWard;

    @BeforeEach
    void setUp() {
        department = new Department("DEPT-A", "General Medicine");
        inactiveDepartment = new Department("DEPT-B", "Former Department");
        firstWard = new Ward("WARD-A", "First Ward");
        secondWard = new Ward("WARD-B", "Second Ward");
        inactiveWard = new Ward("WARD-C", "Former Ward");
        firstBed = new Bed("01", firstWard);
        inactiveBed = new Bed("02", firstWard);
        secondBed = new Bed("01", secondWard);
        bedInInactiveWard = new Bed("01", inactiveWard);
        ReflectionTestUtils.setField(inactiveDepartment, "active", false);
        ReflectionTestUtils.setField(inactiveWard, "active", false);
        ReflectionTestUtils.setField(inactiveBed, "active", false);
        for (Object entity : new Object[]{inactiveDepartment, department, secondWard, firstWard, inactiveWard,
                secondBed, inactiveBed, firstBed, bedInInactiveWard}) {
            entityManager.persist(entity);
        }
        entityManager.flush();
    }

    @Test
    void returnsDepartmentAndWardCodesAndNamesWithOptionalActiveFilters() throws Exception {
        mockMvc.perform(get("/api/v1/departments"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].departmentCode").value("DEPT-A"))
                .andExpect(jsonPath("$[0].departmentName").value("General Medicine"))
                .andExpect(jsonPath("$[1].active").value(false));
        mockMvc.perform(get("/api/v1/departments").param("active", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(department.getId().toString()));
        mockMvc.perform(get("/api/v1/departments").param("active", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inactiveDepartment.getId().toString()));

        mockMvc.perform(get("/api/v1/wards"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].wardCode").value("WARD-A"))
                .andExpect(jsonPath("$[0].wardName").value("First Ward"));
        mockMvc.perform(get("/api/v1/wards").param("active", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/api/v1/wards").param("active", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inactiveWard.getId().toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"departments", "wards", "beds"})
    void resolvesInactiveReferencesById(String resource) throws Exception {
        UUID id = switch (resource) {
            case "departments" -> inactiveDepartment.getId();
            case "wards" -> inactiveWard.getId();
            default -> inactiveBed.getId();
        };
        mockMvc.perform(get("/api/v1/{resource}/{id}", resource, id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.active").value(false));
    }

    @Test
    void keepsIdenticalBedNumbersInDifferentWardsAndSortsByWardThenNumber() throws Exception {
        mockMvc.perform(get("/api/v1/beds"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].id").value(firstBed.getId().toString()))
                .andExpect(jsonPath("$[1].id").value(inactiveBed.getId().toString()))
                .andExpect(jsonPath("$[2].id").value(secondBed.getId().toString()))
                .andExpect(jsonPath("$[3].id").value(bedInInactiveWard.getId().toString()))
                .andExpect(jsonPath("$[0].bedNumber").value("01"))
                .andExpect(jsonPath("$[2].bedNumber").value("01"));
        mockMvc.perform(get("/api/v1/beds").param("wardId", secondWard.getId().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].wardId").value(secondWard.getId().toString()));
    }

    @Test
    void combinesWardActiveAndOccupancyFilters() throws Exception {
        enterFirstBed();
        mockMvc.perform(get("/api/v1/beds").param("wardId", firstWard.getId().toString())
                        .param("active", "true").param("occupied", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstBed.getId().toString()));
        mockMvc.perform(get("/api/v1/beds").param("wardId", firstWard.getId().toString())
                        .param("active", "true").param("occupied", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mockMvc.perform(get("/api/v1/beds").param("wardId", firstWard.getId().toString())
                        .param("active", "false").param("occupied", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(inactiveBed.getId().toString()));
        mockMvc.perform(get("/api/v1/beds").param("occupied", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void reflectsAdmissionTransferDischargeAndDischargeCancellation() throws Exception {
        Encounter encounter = enterFirstBed();
        assertOccupied(firstBed, true);
        assertOccupied(secondBed, false);

        encounterService.transferEncounter(encounter.getId(), department.getId(), secondWard.getId(),
                secondBed.getId(), ADMITTED_AT.plusHours(2));
        assertOccupied(firstBed, false);
        assertOccupied(secondBed, true);

        encounterService.dischargeEncounter(encounter.getId(), ADMITTED_AT.plusHours(3));
        assertOccupied(secondBed, false);
        encounterService.cancelDischarge(encounter.getId(), ADMITTED_AT.plusHours(4), "test-clerk");
        assertOccupied(secondBed, true);
        mockMvc.perform(get("/api/v1/beds").param("occupied", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(secondBed.getId().toString()));
    }

    @Test
    void anUnassignedPatientDoesNotOccupyAnyBed() throws Exception {
        Encounter encounter = admit();
        encounterService.admitToDepartment(encounter.getId(), department.getId(), firstWard.getId(), null,
                ADMITTED_AT.plusHours(1));

        mockMvc.perform(get("/api/v1/beds").param("occupied", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void closedHistoryDoesNotCountAsCurrentOccupancyButStillPreventsBackdatedAssignment() throws Exception {
        Encounter first = enterFirstBed();
        encounterService.dischargeEncounter(first.getId(), ADMITTED_AT.plusHours(3));
        assertOccupied(firstBed, false);
        Encounter next = admit();

        assertThatThrownBy(() -> encounterService.admitToDepartment(next.getId(), department.getId(), firstWard.getId(),
                firstBed.getId(), ADMITTED_AT.plusHours(2))).isInstanceOf(BedHistoryConflictException.class);
    }

    @Test
    void bedActiveIsIndependentOfWardActiveAndDoesNotGuaranteeAssignment() throws Exception {
        mockMvc.perform(get("/api/v1/beds").param("wardId", inactiveWard.getId().toString()).param("active", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].occupied").value(false));
        Encounter encounter = admit();

        assertThatThrownBy(() -> encounterService.admitToDepartment(encounter.getId(), department.getId(), inactiveWard.getId(),
                bedInInactiveWard.getId(), ADMITTED_AT.plusHours(1))).isInstanceOf(InvalidLocationException.class);
    }

    @Test
    void anInactiveBedCanStillHaveAnOpenOccupancyRecord() throws Exception {
        enterFirstBed();
        ReflectionTestUtils.setField(firstBed, "active", false);
        entityManager.flush();

        mockMvc.perform(get("/api/v1/beds").param("active", "false").param("occupied", "true"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(firstBed.getId().toString()))
                .andExpect(jsonPath("$[0].active").value(false))
                .andExpect(jsonPath("$[0].occupied").value(true));
    }

    @Test
    void returnsAnEmptyListForAnUnknownWardFilter() throws Exception {
        mockMvc.perform(get("/api/v1/beds").param("wardId", UUID.randomUUID().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"departments", "wards", "beds"})
    void returnsNotFoundForUnknownDetailIds(String resource) throws Exception {
        mockMvc.perform(get("/api/v1/{resource}/{id}", resource, UUID.randomUUID()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Location not found"));
    }

    @Test
    void rejectsMalformedIdsAndFilterValues() throws Exception {
        for (String path : new String[]{"/departments/not-a-uuid", "/wards/not-a-uuid", "/beds/not-a-uuid",
                "/beds?wardId=not-a-uuid", "/departments?active=maybe", "/wards?active=maybe",
                "/beds?active=maybe", "/beds?occupied=maybe"}) {
            mockMvc.perform(get("/api/v1" + path)).andExpect(status().isBadRequest());
        }
    }

    private void assertOccupied(Bed bed, boolean occupied) throws Exception {
        mockMvc.perform(get("/api/v1/beds/{id}", bed.getId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.occupied").value(occupied));
    }

    private Encounter enterFirstBed() {
        Encounter encounter = admit();
        encounterService.admitToDepartment(encounter.getId(), department.getId(), firstWard.getId(), firstBed.getId(),
                ADMITTED_AT.plusHours(1));
        return encounter;
    }

    private Encounter admit() {
        Patient patient = new Patient("MRN-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 5, 14));
        entityManager.persist(patient);
        entityManager.flush();
        return encounterService.admitPatient(patient.getId(), "ENC-" + UUID.randomUUID(), ADMITTED_AT);
    }
}
