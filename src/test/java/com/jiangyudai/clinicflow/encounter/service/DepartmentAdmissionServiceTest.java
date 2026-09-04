package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterLocationExistsException;
import com.jiangyudai.clinicflow.encounter.exception.BedOccupiedException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDepartmentAdmissionTimeException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.exception.InvalidLocationException;
import com.jiangyudai.clinicflow.location.service.LocationService;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DepartmentAdmissionServiceTest {

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    private static final UUID DEPARTMENT_ID = UUID.fromString(
            "22222222-2222-2222-2222-222222222222"
    );

    private static final UUID WARD_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333"
    );

    private static final UUID BED_ID = UUID.fromString(
            "44444444-4444-4444-4444-444444444444"
    );

    private static final OffsetDateTime ADMITTED_AT =
            OffsetDateTime.parse("2025-09-01T14:00:00-04:00");

    private static final OffsetDateTime STARTED_AT =
            ADMITTED_AT.plusHours(1);

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

    private final Department department =
            new Department("CARD", "Cardiology");

    private final Ward ward =
            new Ward("WARD-A", "General Inpatient Ward");

    private final Bed bed = new Bed("01", ward);

    private final Encounter encounter = new Encounter(
            "ENC-2025-000001",
            new Patient(
                    "MRN-300001",
                    "Maya",
                    "Chen",
                    LocalDate.of(1990, 5, 14)
            ),
            ADMITTED_AT
    );

    @Test
    void admitsToDepartmentWithBed() {
        stubEncounter();
        stubDepartmentAndWard();

        when(locationService.getActiveBed(BED_ID, WARD_ID))
                .thenReturn(bed);
        when(encounterLocationRepository.save(any(EncounterLocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EncounterLocation location = encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                STARTED_AT
        );

        assertThat(location.getEncounter()).isSameAs(encounter);
        assertThat(location.getDepartment()).isSameAs(department);
        assertThat(location.getWard()).isSameAs(ward);
        assertThat(location.getBed()).isSameAs(bed);
        assertThat(location.getStartedAt()).isEqualTo(STARTED_AT);
        assertThat(location.getEndedAt()).isNull();
        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.IN_DEPARTMENT);

        verify(encounterLocationRepository).save(location);
        verify(encounterLocationRepository)
                .existsByBed_IdAndEndedAtIsNull(BED_ID);
    }

    @Test
    void admitsToDepartmentWithoutBed() {
        stubEncounter();
        stubDepartmentAndWard();

        when(encounterLocationRepository.save(any(EncounterLocation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EncounterLocation location = encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                null,
                STARTED_AT
        );

        assertThat(location.getBed()).isNull();
        assertThat(location.getWard()).isSameAs(ward);
        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.IN_DEPARTMENT);

        verify(encounterLocationRepository).save(location);
        verify(locationService, never()).getActiveBed(any(), any());
        verify(encounterLocationRepository, never())
                .existsByBed_IdAndEndedAtIsNull(any());
    }

    @Test
    void rejectsMissingDepartmentAdmissionTime() {
        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                null
        )).isInstanceOf(InvalidDepartmentAdmissionTimeException.class)
                .hasMessageContaining("required");

        verifyNoInteractions(
                encounterRepository,
                encounterLocationRepository,
                locationService
        );
    }

    @Test
    void rejectsDepartmentAdmissionBeforeHospitalAdmission() {
        stubEncounter();

        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                ADMITTED_AT.minusMinutes(1)
        )).isInstanceOf(InvalidDepartmentAdmissionTimeException.class)
                .hasMessageContaining("before hospital admission");

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.ADMITTED);
        verifyNoInteractions(encounterLocationRepository, locationService);
    }

    @Test
    void rejectsFutureDepartmentAdmissionTime() {
        stubEncounter();

        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                OffsetDateTime.now().plusDays(1)
        )).isInstanceOf(InvalidDepartmentAdmissionTimeException.class)
                .hasMessageContaining("future");

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.ADMITTED);
        verifyNoInteractions(encounterLocationRepository, locationService);
    }

    @Test
    void rejectsUnknownEncounter() {
        when(encounterRepository.findById(ENCOUNTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                STARTED_AT
        )).isInstanceOf(EncounterNotFoundException.class);

        verifyNoInteractions(encounterLocationRepository, locationService);
    }

    @Test
    void rejectsEncounterAlreadyInDepartment() {
        encounter.admitToDepartment();
        stubEncounter();

        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                STARTED_AT
        )).isInstanceOf(InvalidEncounterStatusException.class);

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.IN_DEPARTMENT);
        verifyNoInteractions(encounterLocationRepository, locationService);
    }

    @Test
    void rejectsExistingCurrentLocation() {
        stubEncounter();

        when(encounterLocationRepository
                .existsByEncounter_IdAndEndedAtIsNull(ENCOUNTER_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                STARTED_AT
        )).isInstanceOf(ActiveEncounterLocationExistsException.class);

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.ADMITTED);
        verifyNoInteractions(locationService);
        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }

    @Test
    void rejectsOccupiedBed() {
        stubEncounter();
        stubDepartmentAndWard();

        when(locationService.getActiveBed(BED_ID, WARD_ID))
                .thenReturn(bed);
        when(encounterLocationRepository
                .existsByBed_IdAndEndedAtIsNull(BED_ID))
                .thenReturn(true);

        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                STARTED_AT
        )).isInstanceOf(BedOccupiedException.class);

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.ADMITTED);
        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }

    @Test
    void rejectsInvalidDepartmentWithoutChangingEncounter() {
        stubEncounter();

        when(locationService.getActiveDepartment(DEPARTMENT_ID))
                .thenThrow(new InvalidLocationException(
                        "Department is inactive: " + DEPARTMENT_ID
                ));

        assertThatThrownBy(() -> encounterService.admitToDepartment(
                ENCOUNTER_ID,
                DEPARTMENT_ID,
                WARD_ID,
                BED_ID,
                STARTED_AT
        )).isInstanceOf(InvalidLocationException.class);

        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.ADMITTED);
        verify(locationService, never()).getActiveWard(any());
        verify(encounterLocationRepository, never())
                .save(any(EncounterLocation.class));
    }

    private void stubEncounter() {
        when(encounterRepository.findById(ENCOUNTER_ID))
                .thenReturn(Optional.of(encounter));
    }

    private void stubDepartmentAndWard() {
        when(locationService.getActiveDepartment(DEPARTMENT_ID))
                .thenReturn(department);
        when(locationService.getActiveWard(WARD_ID))
                .thenReturn(ward);
    }
}