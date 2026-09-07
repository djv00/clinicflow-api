package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.EncounterLocationHistoryExistsException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionCancellationException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.service.LocationService;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdmissionCancellationServiceTest {

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );
    private static final OffsetDateTime ADMITTED_AT =
            OffsetDateTime.parse("2025-09-01T14:00:00-04:00");
    private static final OffsetDateTime CANCELLED_AT = ADMITTED_AT.plusHours(1);

    @Mock
    private EncounterRepository encounterRepository;
    @Mock
    private EncounterLocationRepository encounterLocationRepository;
    @Mock
    private PatientService patientService;
    @Mock
    private LocationService locationService;
    @InjectMocks
    private EncounterService encounterService;

    private Encounter encounter;

    @BeforeEach
    void setUp() {
        Patient patient = new Patient(
                "MRN-700002", "Maya", "Chen", LocalDate.of(1990, 5, 14)
        );
        encounter = new Encounter("ENC-2025-000006", patient, ADMITTED_AT);
    }

    @Test
    void cancelsTheLockedEncounterAndChecksAllLocationHistory() {
        stubEncounter();

        Encounter result = encounterService.cancelAdmission(ENCOUNTER_ID, CANCELLED_AT, "demo-clerk");

        assertThat(result).isSameAs(encounter);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMISSION_CANCELLED);
        assertThat(encounter.getAdmissionCancelledAt()).isEqualTo(CANCELLED_AT);
        assertThat(encounter.getAdmissionCancelledBy()).isEqualTo("demo-clerk");
        verify(encounterRepository).findByIdForUpdate(ENCOUNTER_ID);
        verify(encounterLocationRepository).existsByEncounter_Id(ENCOUNTER_ID);
        verifyNoInteractions(patientService, locationService);
    }

    @Test
    void rejectsUnknownEncounter() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                encounterService.cancelAdmission(ENCOUNTER_ID, CANCELLED_AT, "demo-clerk")
        ).isInstanceOf(EncounterNotFoundException.class);

        verifyNoInteractions(encounterLocationRepository);
    }

    @Test
    void rejectsDepartmentAdmissionBeforeReadingHistory() {
        encounter.admitToDepartment();
        stubEncounter();

        assertThatThrownBy(() ->
                encounterService.cancelAdmission(ENCOUNTER_ID, CANCELLED_AT, "demo-clerk")
        ).isInstanceOf(InvalidEncounterStatusException.class);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        verifyNoInteractions(encounterLocationRepository);
    }

    @Test
    void rejectsAnyLocationHistoryWithoutChangingTheEncounter() {
        stubEncounter();
        when(encounterLocationRepository.existsByEncounter_Id(ENCOUNTER_ID)).thenReturn(true);

        assertThatThrownBy(() ->
                encounterService.cancelAdmission(ENCOUNTER_ID, CANCELLED_AT, "demo-clerk")
        ).isInstanceOf(EncounterLocationHistoryExistsException.class);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(encounter.getAdmissionCancelledAt()).isNull();
        assertThat(encounter.getAdmissionCancelledBy()).isNull();
    }

    @Test
    void appliesTimeValidationToCallsOutsideTheController() {
        stubEncounter();

        assertThatThrownBy(() ->
                encounterService.cancelAdmission(ENCOUNTER_ID, ADMITTED_AT.minusSeconds(1), "demo-clerk")
        ).isInstanceOf(InvalidAdmissionCancellationException.class);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.ADMITTED);
        assertThat(encounter.getAdmissionCancelledAt()).isNull();
    }

    private void stubEncounter() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID)).thenReturn(Optional.of(encounter));
    }
}
