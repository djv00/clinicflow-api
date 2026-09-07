package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.CurrentEncounterLocationNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.EncounterNotFoundException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidDischargeTimeException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidEncounterStatusException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterLocationRepository;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.service.LocationService;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EncounterDischargeServiceTest {

    private static final UUID ENCOUNTER_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );
    private static final OffsetDateTime STARTED_AT =
            OffsetDateTime.parse("2025-09-02T15:00:00-04:00");
    private static final OffsetDateTime DISCHARGED_AT = STARTED_AT.plusDays(1);

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
    private EncounterLocation currentLocation;

    @BeforeEach
    void setUp() {
        Patient patient = new Patient(
                "MRN-600002", "Maya", "Chen", LocalDate.of(1990, 5, 14)
        );
        encounter = new Encounter("ENC-2025-000004", patient, STARTED_AT.minusDays(1));
        encounter.admitToDepartment();
        currentLocation = new EncounterLocation(
                encounter,
                new Department("CARD", "Cardiology"),
                new Ward("CARD-WARD", "Cardiology Ward"),
                null,
                STARTED_AT
        );
    }

    @Test
    void dischargesWithoutABedAtTheCurrentLocationStartTime() {
        stubCurrentLocation();

        Encounter result = encounterService.dischargeEncounter(ENCOUNTER_ID, STARTED_AT);

        assertThat(result).isSameAs(encounter);
        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.DISCHARGED);
        assertThat(encounter.getDischargedAt()).isEqualTo(STARTED_AT);
        assertThat(currentLocation.getEndedAt()).isEqualTo(STARTED_AT);
        verifyNoInteractions(locationService, patientService);
    }

    @Test
    void rejectsMissingTimeBeforeLoadingEncounter() {
        assertThatThrownBy(() -> encounterService.dischargeEncounter(ENCOUNTER_ID, null))
                .isInstanceOf(InvalidDischargeTimeException.class);

        verifyNoInteractions(encounterRepository, encounterLocationRepository);
    }

    @Test
    void rejectsUnknownEncounter() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                encounterService.dischargeEncounter(ENCOUNTER_ID, DISCHARGED_AT)
        ).isInstanceOf(EncounterNotFoundException.class);

        verifyNoInteractions(encounterLocationRepository);
    }

    @ParameterizedTest
    @EnumSource(value = EncounterStatus.class, names = {
            "ADMITTED", "DISCHARGED", "ADMISSION_CANCELLED"
    })
    void rejectsInvalidStateBeforeLoadingLocation(EncounterStatus status) {
        ReflectionTestUtils.setField(encounter, "status", status);
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.of(encounter));

        assertThatThrownBy(() ->
                encounterService.dischargeEncounter(ENCOUNTER_ID, DISCHARGED_AT)
        ).isInstanceOf(InvalidEncounterStatusException.class);

        assertThat(encounter.getStatus()).isEqualTo(status);
        verifyNoInteractions(encounterLocationRepository);
    }

    @Test
    void rejectsMissingCurrentLocation() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.of(encounter));
        when(encounterLocationRepository.findByEncounter_IdAndEndedAtIsNull(ENCOUNTER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                encounterService.dischargeEncounter(ENCOUNTER_ID, DISCHARGED_AT)
        ).isInstanceOf(CurrentEncounterLocationNotFoundException.class);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        assertThat(encounter.getDischargedAt()).isNull();
    }

    @ParameterizedTest
    @MethodSource("invalidDischargeTimes")
    void rejectsInvalidTimeWithoutClosingLocation(OffsetDateTime dischargedAt) {
        stubCurrentLocation();

        assertThatThrownBy(() ->
                encounterService.dischargeEncounter(ENCOUNTER_ID, dischargedAt)
        ).isInstanceOf(InvalidDischargeTimeException.class);

        assertThat(encounter.getStatus()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
        assertThat(encounter.getDischargedAt()).isNull();
        assertThat(currentLocation.getEndedAt()).isNull();
    }

    private static Stream<OffsetDateTime> invalidDischargeTimes() {
        return Stream.of(STARTED_AT.minusSeconds(1), OffsetDateTime.now().plusDays(1));
    }

    private void stubCurrentLocation() {
        when(encounterRepository.findByIdForUpdate(ENCOUNTER_ID))
                .thenReturn(Optional.of(encounter));
        when(encounterLocationRepository.findByEncounter_IdAndEndedAtIsNull(ENCOUNTER_ID))
                .thenReturn(Optional.of(currentLocation));
    }
}
