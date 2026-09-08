package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterDischarge;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterLocationExistsException;
import com.jiangyudai.clinicflow.encounter.exception.DischargeRecordConflictException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterDischargeRepository;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DischargeCancellationServiceTest {

    private static final UUID ENCOUNTER_ID = UUID.randomUUID();
    private static final UUID PATIENT_ID = UUID.randomUUID();
    private static final OffsetDateTime DISCHARGED_AT = OffsetDateTime.parse("2025-09-03T14:00:00-04:00");

    @Mock
    private EncounterRepository encounterRepository;
    @Mock
    private EncounterLocationRepository encounterLocationRepository;
    @Mock
    private EncounterDischargeRepository encounterDischargeRepository;
    @Mock
    private PatientService patientService;
    @Mock
    private LocationService locationService;
    @InjectMocks
    private EncounterService encounterService;

    private Encounter encounter;
    private EncounterLocation previous;
    private EncounterDischarge discharge;
    private Department department;
    private Ward ward;
    private Bed bed;

    @BeforeEach
    void setUp() {
        Patient patient = new Patient("MRN-TEST", "Test", "Patient", LocalDate.of(1990, 5, 14));
        encounter = new Encounter("ENC-TEST", patient, DISCHARGED_AT.minusDays(2));
        ReflectionTestUtils.setField(encounter, "id", ENCOUNTER_ID);
        department = new Department("CARD", "Cardiology");
        ward = new Ward("CARD-WARD", "Cardiology Ward");
        bed = new Bed("01", ward);
        ReflectionTestUtils.setField(department, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(ward, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(bed, "id", UUID.randomUUID());
        previous = new EncounterLocation(encounter, department, ward, bed, DISCHARGED_AT.minusDays(1));
        encounter.admitToDepartment();
        encounter.dischargeAt(DISCHARGED_AT);
        previous.endAt(DISCHARGED_AT);
        discharge = new EncounterDischarge(encounter, previous);
    }

    @Test
    void locksPatientThenEncounterThenBedBeforeRestoringTheRecordedLocation() {
        stubEncounter();
        when(encounterDischargeRepository.findByEncounter_IdAndCancelledAtIsNull(ENCOUNTER_ID))
                .thenReturn(Optional.of(discharge));
        when(locationService.getActiveDepartment(department.getId())).thenReturn(department);
        when(locationService.getActiveWard(ward.getId())).thenReturn(ward);
        when(locationService.getActiveBedForUpdate(bed.getId(), ward.getId())).thenReturn(bed);

        Encounter result = encounterService.cancelDischarge(ENCOUNTER_ID, DISCHARGED_AT.plusHours(1), "demo-clerk");

        assertThat(result).isSameAs(encounter);
        assertThat(result.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        EncounterLocation restored = discharge.getRestoredLocation();
        assertThat(restored.getDepartment()).isSameAs(department);
        assertThat(restored.getWard()).isSameAs(ward);
        assertThat(restored.getBed()).isSameAs(bed);
        assertThat(restored.getStartedAt()).isEqualTo(DISCHARGED_AT);
        assertThat(discharge.getCancelledAt()).isEqualTo(DISCHARGED_AT.plusHours(1));
        var order = inOrder(patientService, encounterRepository, locationService, encounterLocationRepository);
        order.verify(patientService).getPatientForUpdate(PATIENT_ID);
        order.verify(encounterRepository).findByIdForUpdate(ENCOUNTER_ID);
        order.verify(encounterRepository).existsConflictingEncounterAfterDischarge(
                PATIENT_ID, ENCOUNTER_ID, DISCHARGED_AT, EncounterStatus.ADMISSION_CANCELLED
        );
        order.verify(locationService).getActiveBedForUpdate(bed.getId(), ward.getId());
        order.verify(encounterLocationRepository).existsByBed_IdAndEndedAtIsNull(bed.getId());
        order.verify(encounterLocationRepository).existsClosedBedHistoryAfter(bed.getId(), DISCHARGED_AT);
        order.verify(encounterLocationRepository).save(restored);
    }

    @Test
    void rejectsUnknownEncounterBeforeTakingOtherLocks() {
        assertThatThrownBy(() -> cancel()).isInstanceOf(EncounterNotFoundException.class);
        verifyNoInteractions(patientService, locationService, encounterDischargeRepository, encounterLocationRepository);
    }

    @Test
    void refusesAnExistingCurrentLocation() {
        stubEncounter();
        when(encounterLocationRepository.existsByEncounter_IdAndEndedAtIsNull(ENCOUNTER_ID)).thenReturn(true);
        assertThatThrownBy(() -> cancel()).isInstanceOf(ActiveEncounterLocationExistsException.class);
        verifyNoInteractions(encounterDischargeRepository, locationService);
        assertUnchanged();
    }

    @Test
    void refusesMissingDischargeInsteadOfGuessingFromLocationTimes() {
        stubEncounter();
        assertThatThrownBy(() -> cancel()).isInstanceOf(DischargeRecordConflictException.class);
        verifyNoInteractions(locationService);
        assertUnchanged();
    }

    @Test
    void refusesDischargeWhoseClosedLocationDoesNotMatch() {
        stubEncounter();
        when(encounterDischargeRepository.findByEncounter_IdAndCancelledAtIsNull(ENCOUNTER_ID))
                .thenReturn(Optional.of(discharge));
        ReflectionTestUtils.setField(previous, "endedAt", DISCHARGED_AT.minusMinutes(1));

        assertThatThrownBy(() -> cancel()).isInstanceOf(DischargeRecordConflictException.class);
        verifyNoInteractions(locationService);
        assertUnchanged();
    }

    private void stubEncounter() {
        when(encounterRepository.findPatientIdById(ENCOUNTER_ID)).thenReturn(Optional.of(PATIENT_ID));
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID)).thenReturn(Optional.of(encounter));
    }

    private void cancel() {
        encounterService.cancelDischarge(ENCOUNTER_ID, DISCHARGED_AT.plusHours(1), "demo-clerk");
    }

    private void assertUnchanged() {
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
        assertThat(encounter.getDischargedAt()).isEqualTo(DISCHARGED_AT);
        assertThat(discharge.getCancelledAt()).isNull();
        assertThat(discharge.getCancelledBy()).isNull();
        assertThat(discharge.getRestoredLocation()).isNull();
    }
}
