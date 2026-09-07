package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.*;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.service.LocationService;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EncounterTransferServiceTest {
    private Department currentDepartment;
    private Ward currentWard;
    private Bed currentBed;

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID CURRENT_DEPARTMENT_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );

    private static final UUID CURRENT_WARD_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );

    private static final UUID CURRENT_BED_ID = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
    );

    private static final UUID TARGET_DEPARTMENT_ID = UUID.fromString(
            "55555555-5555-5555-5555-555555555555"
    );

    private static final UUID TARGET_WARD_ID = UUID.fromString(
            "66666666-6666-6666-6666-666666666666"
    );

    private static final UUID TARGET_BED_ID = UUID.fromString(
            "77777777-7777-7777-7777-777777777777"
    );

    private static final OffsetDateTime ADMITTED_AT =
            OffsetDateTime.parse("2025-09-01T14:00:00-04:00");

    private static final OffsetDateTime CURRENT_STARTED_AT =
            ADMITTED_AT.plusHours(1);

    private static final OffsetDateTime TRANSFERRED_AT =
            CURRENT_STARTED_AT.plusDays(1);

    @Mock
    private EncounterRepository encounterRepository;

    @Mock
    private PatientService patientService;

    @Mock
    private EncounterLocationRepository encounterLocationRepository;

    @Mock
    private LocationService locationService;

    @InjectMocks
    private EncounterService encounterService;

    private Encounter encounter;
    private EncounterLocation currentLocation;

    private Department targetDepartment;
    private Ward targetWard;
    private Bed targetBed;

    @BeforeEach
    void setUp() {
        currentDepartment = new Department(
                "CARD",
                "Cardiology"
        );
        currentWard = new Ward(
                "CARD-WARD",
                "Cardiology Ward"
        );
        currentBed = new Bed("01", currentWard);

        targetDepartment = new Department(
                "NEUR",
                "Neurology"
        );
        targetWard = new Ward(
                "NEUR-WARD",
                "Neurology Ward"
        );
        targetBed = new Bed("02", targetWard);

        ReflectionTestUtils.setField(
                currentDepartment,
                "id",
                CURRENT_DEPARTMENT_ID
        );
        ReflectionTestUtils.setField(
                currentWard,
                "id",
                CURRENT_WARD_ID
        );
        ReflectionTestUtils.setField(
                currentBed,
                "id",
                CURRENT_BED_ID
        );
        ReflectionTestUtils.setField(
                targetDepartment,
                "id",
                TARGET_DEPARTMENT_ID
        );
        ReflectionTestUtils.setField(
                targetWard,
                "id",
                TARGET_WARD_ID
        );
        ReflectionTestUtils.setField(
                targetBed,
                "id",
                TARGET_BED_ID
        );

        Patient patient = new Patient(
                "MRN-500001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        );

        encounter = new Encounter(
                "ENC-2025-000002",
                patient,
                ADMITTED_AT
        );
        encounter.admitToDepartment();

        currentLocation = new EncounterLocation(
                encounter,
                currentDepartment,
                currentWard,
                currentBed,
                CURRENT_STARTED_AT
        );
    }

    @Test
    void transfersEncounterToAnotherLocationWithBed() {
        stubCurrentEncounterAndLocation();
        stubTargetDepartmentAndWard();

        when(locationService.getActiveBedForUpdate(
                TARGET_BED_ID,
                TARGET_WARD_ID
        )).thenReturn(targetBed);

        when(encounterLocationRepository.save(
                any(EncounterLocation.class)
        )).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterLocation nextLocation =
                encounterService.transferEncounter(
                        ENCOUNTER_ID,
                        TARGET_DEPARTMENT_ID,
                        TARGET_WARD_ID,
                        TARGET_BED_ID,
                        TRANSFERRED_AT
                );

        assertThat(currentLocation.getEndedAt())
                .isEqualTo(TRANSFERRED_AT);

        assertThat(nextLocation.getEncounter()).isSameAs(encounter);
        assertThat(nextLocation.getDepartment())
                .isSameAs(targetDepartment);
        assertThat(nextLocation.getWard()).isSameAs(targetWard);
        assertThat(nextLocation.getBed()).isSameAs(targetBed);
        assertThat(nextLocation.getStartedAt())
                .isEqualTo(TRANSFERRED_AT);
        assertThat(nextLocation.getEndedAt()).isNull();

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.IN_DEPARTMENT);

        verify(encounterLocationRepository).save(nextLocation);
        verify(encounterLocationRepository)
                .existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
                        TARGET_BED_ID,
                        ENCOUNTER_ID
                );
    }

    @Test
    void transfersEncounterWithoutTargetBed() {
        stubCurrentEncounterAndLocation();
        stubTargetDepartmentAndWard();

        when(encounterLocationRepository.save(
                any(EncounterLocation.class)
        )).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterLocation nextLocation =
                encounterService.transferEncounter(
                        ENCOUNTER_ID,
                        TARGET_DEPARTMENT_ID,
                        TARGET_WARD_ID,
                        null,
                        TRANSFERRED_AT
                );

        assertThat(currentLocation.getEndedAt())
                .isEqualTo(TRANSFERRED_AT);
        assertThat(nextLocation.getDepartment())
                .isSameAs(targetDepartment);
        assertThat(nextLocation.getWard()).isSameAs(targetWard);
        assertThat(nextLocation.getBed()).isNull();
        assertThat(nextLocation.getStartedAt())
                .isEqualTo(TRANSFERRED_AT);

        verify(locationService, never())
                .getActiveBedForUpdate(any(), any());
        verify(encounterLocationRepository, never())
                .existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
                        any(),
                        any()
                );
        verify(encounterLocationRepository).save(nextLocation);
    }


    @Test
    void rejectsMissingTransferTime() {
        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                TARGET_DEPARTMENT_ID,
                TARGET_WARD_ID,
                TARGET_BED_ID,
                null
        )).isInstanceOf(InvalidEncounterTransferTimeException.class)
                .hasMessageContaining("required");

        verifyNoInteractions(
                encounterRepository,
                encounterLocationRepository,
                locationService
        );
    }

    @Test
    void rejectsUnknownEncounter() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                TARGET_DEPARTMENT_ID,
                TARGET_WARD_ID,
                TARGET_BED_ID,
                TRANSFERRED_AT
        )).isInstanceOf(EncounterNotFoundException.class);

        verifyNoInteractions(
                encounterLocationRepository,
                locationService
        );
    }

    @Test
    void rejectsEncounterOutsideDepartment() {
        Encounter admittedEncounter = new Encounter(
                "ENC-2025-000003",
                new Patient(
                        "MRN-500002",
                        "Lucas",
                        "Martin",
                        LocalDate.of(1985, 8, 20)
                ),
                ADMITTED_AT
        );

        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.of(admittedEncounter));

        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                TARGET_DEPARTMENT_ID,
                TARGET_WARD_ID,
                TARGET_BED_ID,
                TRANSFERRED_AT
        )).isInstanceOf(InvalidEncounterStatusException.class)
                .hasMessageContaining("IN_DEPARTMENT");

        verifyNoInteractions(
                encounterLocationRepository,
                locationService
        );
    }

    @Test
    void rejectsMissingCurrentLocation() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.of(encounter));

        when(encounterLocationRepository
                .findByEncounter_IdAndEndedAtIsNull(ENCOUNTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                TARGET_DEPARTMENT_ID,
                TARGET_WARD_ID,
                TARGET_BED_ID,
                TRANSFERRED_AT
        )).isInstanceOf(
                CurrentEncounterLocationNotFoundException.class
        );

        verifyNoInteractions(locationService);
        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }

    @Test
    void rejectsTransferBeforeCurrentLocationStart() {
        stubCurrentEncounterAndLocation();

        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                TARGET_DEPARTMENT_ID,
                TARGET_WARD_ID,
                TARGET_BED_ID,
                CURRENT_STARTED_AT.minusSeconds(1)
        )).isInstanceOf(InvalidEncounterTransferTimeException.class)
                .hasMessageContaining(
                        "before the current location start time"
                );

        assertThat(currentLocation.getEndedAt()).isNull();
        verifyNoInteractions(locationService);
        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }

    @Test
    void rejectsFutureTransferTime() {
        stubCurrentEncounterAndLocation();

        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                TARGET_DEPARTMENT_ID,
                TARGET_WARD_ID,
                TARGET_BED_ID,
                OffsetDateTime.now().plusDays(1)
        )).isInstanceOf(InvalidEncounterTransferTimeException.class)
                .hasMessageContaining("future");

        assertThat(currentLocation.getEndedAt()).isNull();
        verifyNoInteractions(locationService);
        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }


    @Test
    void transfersEncounterToAnotherBedInSameWard() {
        Bed nextBed = new Bed("02", currentWard);
        ReflectionTestUtils.setField(
                nextBed,
                "id",
                TARGET_BED_ID
        );

        stubCurrentEncounterAndLocation();

        when(locationService.getActiveDepartment(CURRENT_DEPARTMENT_ID))
                .thenReturn(currentDepartment);
        when(locationService.getActiveWard(CURRENT_WARD_ID))
                .thenReturn(currentWard);
        when(locationService.getActiveBedForUpdate(
                TARGET_BED_ID,
                CURRENT_WARD_ID
        )).thenReturn(nextBed);

        when(encounterLocationRepository.save(
                any(EncounterLocation.class)
        )).thenAnswer(invocation -> invocation.getArgument(0));

        EncounterLocation nextLocation =
                encounterService.transferEncounter(
                        ENCOUNTER_ID,
                        CURRENT_DEPARTMENT_ID,
                        CURRENT_WARD_ID,
                        TARGET_BED_ID,
                        TRANSFERRED_AT
                );

        assertThat(currentLocation.getEndedAt())
                .isEqualTo(TRANSFERRED_AT);
        assertThat(nextLocation.getDepartment())
                .isSameAs(currentDepartment);
        assertThat(nextLocation.getWard()).isSameAs(currentWard);
        assertThat(nextLocation.getBed()).isSameAs(nextBed);

        verify(encounterLocationRepository)
                .existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
                        TARGET_BED_ID,
                        ENCOUNTER_ID
                );
        verify(encounterLocationRepository).save(nextLocation);
    }

    @Test
    void rejectsTransferToSameLocation() {
        stubCurrentEncounterAndLocation();

        when(locationService.getActiveDepartment(CURRENT_DEPARTMENT_ID))
                .thenReturn(currentDepartment);
        when(locationService.getActiveWard(CURRENT_WARD_ID))
                .thenReturn(currentWard);
        when(locationService.getActiveBedForUpdate(
                CURRENT_BED_ID,
                CURRENT_WARD_ID
        )).thenReturn(currentBed);

        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                CURRENT_DEPARTMENT_ID,
                CURRENT_WARD_ID,
                CURRENT_BED_ID,
                TRANSFERRED_AT
        )).isInstanceOf(SameEncounterLocationException.class);

        assertThat(currentLocation.getEndedAt()).isNull();

        verify(encounterLocationRepository, never())
                .existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
                        any(),
                        any()
                );
        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }

    @Test
    void rejectsOccupiedTargetBedWithoutEndingCurrentLocation() {
        stubCurrentEncounterAndLocation();
        stubTargetDepartmentAndWard();

        when(locationService.getActiveBedForUpdate(
                TARGET_BED_ID,
                TARGET_WARD_ID
        )).thenReturn(targetBed);

        when(encounterLocationRepository
                .existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
                        TARGET_BED_ID,
                        ENCOUNTER_ID
                ))
                .thenReturn(true);

        assertThatThrownBy(() -> encounterService.transferEncounter(
                ENCOUNTER_ID,
                TARGET_DEPARTMENT_ID,
                TARGET_WARD_ID,
                TARGET_BED_ID,
                TRANSFERRED_AT
        )).isInstanceOf(BedOccupiedException.class);

        assertThat(currentLocation.getEndedAt()).isNull();

        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }


    private void stubCurrentEncounterAndLocation() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.of(encounter));

        when(encounterLocationRepository
                .findByEncounter_IdAndEndedAtIsNull(ENCOUNTER_ID))
                .thenReturn(Optional.of(currentLocation));
    }

    private void stubTargetDepartmentAndWard() {
        when(locationService.getActiveDepartment(TARGET_DEPARTMENT_ID))
                .thenReturn(targetDepartment);

        when(locationService.getActiveWard(TARGET_WARD_ID))
                .thenReturn(targetWard);
    }
}