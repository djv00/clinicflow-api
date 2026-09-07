package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterExistsException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDepartmentAdmissionTimeException;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.exception.LocationNotFoundException;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterTransferTimeException;
import com.jiangyudai.clinicflow.encounter.exception.SameEncounterLocationException;

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

    private static final UUID DEPARTMENT_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );

    private static final UUID WARD_ID = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
    );

    private static final UUID LOCATION_ID = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
    );

    private static final OffsetDateTime DEPARTMENT_ADMITTED_AT =
            OffsetDateTime.parse("2025-09-02T18:30:00-04:00");


    private static final UUID BED_ID = UUID.fromString(
            "66666666-6666-6666-6666-666666666666"
    );

    private static final String TRANSFERRED_AT_JSON =
            "2025-09-03T10:00:00-04:00";

    private static final OffsetDateTime TRANSFERRED_AT =
            OffsetDateTime.parse(TRANSFERRED_AT_JSON);

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

    @Test
    void admitsToDepartmentWithoutBed() throws Exception {
        EncounterLocation location = createLocationWithoutBed();

        when(encounterService.admitToDepartment(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                isNull(),
                argThat(time ->
                        time.isEqual(DEPARTMENT_ADMITTED_AT)
                )
        )).thenReturn(location);

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/department-admissions",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validDepartmentAdmissionJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.encounterId")
                        .value(ENCOUNTER_ID.toString()))
                .andExpect(jsonPath("$.departmentId")
                        .value(DEPARTMENT_ID.toString()))
                .andExpect(jsonPath("$.wardId")
                        .value(WARD_ID.toString()))
                .andExpect(jsonPath("$.bedId").value(nullValue()));

        verify(encounterService).admitToDepartment(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                isNull(),
                argThat(time ->
                        time.isEqual(DEPARTMENT_ADMITTED_AT)
                )
        );
    }

    @Test
    void rejectsMissingDepartmentAdmissionFields() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/department-admissions",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title")
                        .value("Invalid request"))
                .andExpect(jsonPath("$.errors.departmentId")
                        .value("Department ID is required"))
                .andExpect(jsonPath("$.errors.wardId")
                        .value("Ward ID is required"))
                .andExpect(jsonPath("$.errors.startedAt")
                        .value("Department admission time is required"));
    }

    @Test
    void returnsNotFoundWhenDepartmentIsMissing() throws Exception {
        when(encounterService.admitToDepartment(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                isNull(),
                argThat(time ->
                        time.isEqual(DEPARTMENT_ADMITTED_AT)
                )
        )).thenThrow(
                new LocationNotFoundException(
                        "Department",
                        DEPARTMENT_ID
                )
        );

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/department-admissions",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validDepartmentAdmissionJson()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title")
                        .value("Location not found"))
                .andExpect(jsonPath("$.detail")
                        .value("Department not found: " + DEPARTMENT_ID));
    }

    @Test
    void rejectsDepartmentAdmissionBeforeHospitalAdmission() throws Exception {
        OffsetDateTime invalidTime = ADMITTED_AT.minusHours(1);

        when(encounterService.admitToDepartment(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                isNull(),
                argThat(time ->
                        time.isEqual(invalidTime)
                )
        )).thenThrow(
                new InvalidDepartmentAdmissionTimeException(
                        "Department admission time cannot be before hospital admission"
                )
        );

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/department-admissions",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content("""
                                {
                                  "departmentId": "%s",
                                  "wardId": "%s",
                                  "startedAt": "%s"
                                }
                                """.formatted(
                                DEPARTMENT_ID,
                                WARD_ID,
                                invalidTime
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title")
                        .value("Invalid encounter location"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Department admission time cannot be before hospital admission"
                        ));
    }


    @Test
    void transfersEncounterWithBed() throws Exception {
        Bed bed = mock(Bed.class);
        when(bed.getId()).thenReturn(BED_ID);

        EncounterLocation location =
                createTransferredLocation(bed);

        when(encounterService.transferEncounter(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                eq(BED_ID),
                argThat(time -> time.isEqual(TRANSFERRED_AT))
        )).thenReturn(location);

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validTransferJsonWithBed()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.encounterId")
                        .value(ENCOUNTER_ID.toString()))
                .andExpect(jsonPath("$.departmentId")
                        .value(DEPARTMENT_ID.toString()))
                .andExpect(jsonPath("$.wardId")
                        .value(WARD_ID.toString()))
                .andExpect(jsonPath("$.bedId")
                        .value(BED_ID.toString()))
                .andExpect(jsonPath("$.startedAt")
                        .value(TRANSFERRED_AT_JSON))
                .andExpect(jsonPath("$.endedAt")
                        .value(nullValue()));

        verify(encounterService).transferEncounter(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                eq(BED_ID),
                argThat(time -> time.isEqual(TRANSFERRED_AT))
        );
    }


    @Test
    void transfersEncounterWithoutBed() throws Exception {
        EncounterLocation location =
                createTransferredLocation(null);

        when(encounterService.transferEncounter(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                isNull(),
                argThat(time -> time.isEqual(TRANSFERRED_AT))
        )).thenReturn(location);

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validTransferJsonWithoutBed()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id")
                        .value(LOCATION_ID.toString()))
                .andExpect(jsonPath("$.encounterId")
                        .value(ENCOUNTER_ID.toString()))
                .andExpect(jsonPath("$.departmentId")
                        .value(DEPARTMENT_ID.toString()))
                .andExpect(jsonPath("$.wardId")
                        .value(WARD_ID.toString()))
                .andExpect(jsonPath("$.bedId").value(nullValue()))
                .andExpect(jsonPath("$.startedAt")
                        .value(TRANSFERRED_AT_JSON));

        verify(encounterService).transferEncounter(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                isNull(),
                argThat(time -> time.isEqual(TRANSFERRED_AT))
        );
    }


    @Test
    void rejectsMissingTransferFields() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title")
                        .value("Invalid request"))
                .andExpect(jsonPath("$.errors.departmentId")
                        .value("Department ID is required"))
                .andExpect(jsonPath("$.errors.wardId")
                        .value("Ward ID is required"))
                .andExpect(jsonPath("$.errors.transferredAt")
                        .value("Transfer time is required"));

        verifyNoInteractions(encounterService);
    }


    @Test
    void rejectsFutureTransferTimeBeforeCallingService()
            throws Exception {
        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content("""
                        {
                          "departmentId": "%s",
                          "wardId": "%s",
                          "bedId": "%s",
                          "transferredAt": "2099-09-03T10:00:00-04:00"
                        }
                        """.formatted(
                                DEPARTMENT_ID,
                                WARD_ID,
                                BED_ID
                        )))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title")
                        .value("Invalid request"))
                .andExpect(jsonPath("$.errors.transferredAt")
                        .value(
                                "Transfer time cannot be in the future"
                        ));

        verifyNoInteractions(encounterService);
    }


    @Test
    void returnsBadRequestForInvalidTransferTime()
            throws Exception {
        stubTransferFailure(
                new InvalidEncounterTransferTimeException(
                        "Transfer time cannot be before the current location start time"
                )
        );

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validTransferJsonWithBed()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title")
                        .value("Invalid encounter location"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Transfer time cannot be before the current location start time"
                        ));
    }


    @Test
    void returnsConflictForSameEncounterLocation()
            throws Exception {
        stubTransferFailure(
                new SameEncounterLocationException(ENCOUNTER_ID)
        );

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validTransferJsonWithBed()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Encounter location conflict"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Encounter is already assigned to the requested location: "
                                        + ENCOUNTER_ID
                        ));
    }


    @Test
    void returnsConflictWhenTransferBedIsOccupied()
            throws Exception {
        stubTransferFailure(new BedOccupiedException(BED_ID));

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validTransferJsonWithBed()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title")
                        .value("Encounter location conflict"))
                .andExpect(jsonPath("$.detail")
                        .value("Bed is already occupied: " + BED_ID));
    }


    @Test
    void returnsNotFoundWhenTransferDepartmentIsMissing()
            throws Exception {
        stubTransferFailure(
                new LocationNotFoundException(
                        "Department",
                        DEPARTMENT_ID
                )
        );

        mockMvc.perform(post(
                        "/api/v1/encounters/{id}/transfers",
                        ENCOUNTER_ID
                )
                        .contentType("application/json")
                        .content(validTransferJsonWithBed()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title")
                        .value("Location not found"))
                .andExpect(jsonPath("$.detail")
                        .value(
                                "Department not found: " + DEPARTMENT_ID
                        ));
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

    private EncounterLocation createLocationWithoutBed() {
        Encounter encounter = mock(Encounter.class);
        Department department = mock(Department.class);
        Ward ward = mock(Ward.class);
        EncounterLocation location = mock(EncounterLocation.class);

        when(encounter.getId()).thenReturn(ENCOUNTER_ID);
        when(department.getId()).thenReturn(DEPARTMENT_ID);
        when(ward.getId()).thenReturn(WARD_ID);

        when(location.getId()).thenReturn(LOCATION_ID);
        when(location.getEncounter()).thenReturn(encounter);
        when(location.getDepartment()).thenReturn(department);
        when(location.getWard()).thenReturn(ward);
        when(location.getBed()).thenReturn(null);
        when(location.getStartedAt())
                .thenReturn(DEPARTMENT_ADMITTED_AT);
        when(location.getEndedAt()).thenReturn(null);

        return location;
    }

    private EncounterLocation createTransferredLocation(Bed bed) {
        Encounter encounter = mock(Encounter.class);
        Department department = mock(Department.class);
        Ward ward = mock(Ward.class);
        EncounterLocation location = mock(EncounterLocation.class);

        when(encounter.getId()).thenReturn(ENCOUNTER_ID);
        when(department.getId()).thenReturn(DEPARTMENT_ID);
        when(ward.getId()).thenReturn(WARD_ID);

        when(location.getId()).thenReturn(LOCATION_ID);
        when(location.getEncounter()).thenReturn(encounter);
        when(location.getDepartment()).thenReturn(department);
        when(location.getWard()).thenReturn(ward);
        when(location.getBed()).thenReturn(bed);
        when(location.getStartedAt()).thenReturn(TRANSFERRED_AT);
        when(location.getEndedAt()).thenReturn(null);

        return location;
    }

    private String validDepartmentAdmissionJson() {
        return """
                {
                  "departmentId": "%s",
                  "wardId": "%s",
                  "startedAt": "%s"
                }
                """.formatted(
                DEPARTMENT_ID,
                WARD_ID,
                DEPARTMENT_ADMITTED_AT
        );
    }

    private String validTransferJsonWithBed() {
        return """
                {
                  "departmentId": "%s",
                  "wardId": "%s",
                  "bedId": "%s",
                  "transferredAt": "%s"
                }
                """.formatted(
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                TRANSFERRED_AT_JSON
        );
    }

    private String validTransferJsonWithoutBed() {
        return """
                {
                  "departmentId": "%s",
                  "wardId": "%s",
                  "transferredAt": "%s"
                }
                """.formatted(
                DEPARTMENT_ID,
                WARD_ID,
                TRANSFERRED_AT_JSON
        );
    }

    private void stubTransferFailure(RuntimeException exception) {
        when(encounterService.transferEncounter(
                eq(ENCOUNTER_ID),
                eq(DEPARTMENT_ID),
                eq(WARD_ID),
                eq(BED_ID),
                argThat(time -> time.isEqual(TRANSFERRED_AT))
        )).thenThrow(exception);
    }
}
