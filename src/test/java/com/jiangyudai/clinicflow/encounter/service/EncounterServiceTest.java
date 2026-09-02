package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.exception.ActiveEncounterExistsException;
import com.jiangyudai.clinicflow.encounter.exception.DuplicateEncounterNumberException;
import com.jiangyudai.clinicflow.encounter.exception.InvalidAdmissionTimeException;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.exception.PatientNotFoundException;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EncounterServiceTest {

    private static final UUID PATIENT_ID = UUID.fromString(
            "11111111-1111-1111-1111-111111111111"
    );

    private static final List<EncounterStatus> ACTIVE_STATUSES = List.of(
            EncounterStatus.ADMITTED,
            EncounterStatus.IN_DEPARTMENT
    );

    @Mock
    private EncounterRepository encounterRepository;

    @Mock
    private PatientService patientService;

    @InjectMocks
    private EncounterService encounterService;

    @Test
    void admitsPatient() {
        Patient patient = createPatient();

        when(patientService.getPatient(PATIENT_ID))
                .thenReturn(patient);
        when(encounterRepository.existsByEncounterNumber(
                "ENC-2026-000001"
        )).thenReturn(false);
        when(encounterRepository.existsByPatient_IdAndStatusIn(
                PATIENT_ID,
                ACTIVE_STATUSES
        )).thenReturn(false);
        when(encounterRepository.save(any(Encounter.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Encounter encounter = encounterService.admitPatient(
                PATIENT_ID,
                "ENC-2026-000001",
                OffsetDateTime.parse("2025-09-02T16:30:00-04:00")
        );

        assertThat(encounter.getEncounterNumber())
                .isEqualTo("ENC-2026-000001");
        assertThat(encounter.getPatient()).isSameAs(patient);
        assertThat(encounter.getStatus())
                .isEqualTo(EncounterStatus.ADMITTED);

        verify(encounterRepository).save(any(Encounter.class));
    }

    @Test
    void rejectsUnknownPatient() {
        when(patientService.getPatient(PATIENT_ID))
                .thenThrow(new PatientNotFoundException(PATIENT_ID));

        assertThatThrownBy(() -> encounterService.admitPatient(
                PATIENT_ID,
                "ENC-2026-000001",
                OffsetDateTime.parse("2025-09-02T16:30:00-04:00")
        )).isInstanceOf(PatientNotFoundException.class);

        verifyNoInteractions(encounterRepository);
    }

    @Test
    void rejectsDuplicateEncounterNumber() {
        when(patientService.getPatient(PATIENT_ID))
                .thenReturn(createPatient());
        when(encounterRepository.existsByEncounterNumber(
                "ENC-2026-000001"
        )).thenReturn(true);

        assertThatThrownBy(() -> encounterService.admitPatient(
                PATIENT_ID,
                "ENC-2026-000001",
                OffsetDateTime.parse("2025-09-02T16:30:00-04:00")
        )).isInstanceOf(DuplicateEncounterNumberException.class);

        verify(encounterRepository, never()).save(any(Encounter.class));
    }

    @Test
    void rejectsSecondActiveEncounter() {
        when(patientService.getPatient(PATIENT_ID))
                .thenReturn(createPatient());
        when(encounterRepository.existsByEncounterNumber(
                "ENC-2026-000001"
        )).thenReturn(false);
        when(encounterRepository.existsByPatient_IdAndStatusIn(
                PATIENT_ID,
                ACTIVE_STATUSES
        )).thenReturn(true);

        assertThatThrownBy(() -> encounterService.admitPatient(
                PATIENT_ID,
                "ENC-2026-000001",
                OffsetDateTime.parse("2025-09-02T16:30:00-04:00")
        )).isInstanceOf(ActiveEncounterExistsException.class);

        verify(encounterRepository, never()).save(any(Encounter.class));
    }

    @Test
    void rejectsFutureAdmissionTime() {
        assertThatThrownBy(() -> encounterService.admitPatient(
                PATIENT_ID,
                "ENC-2026-000001",
                OffsetDateTime.parse("2099-09-02T16:30:00-04:00")
        )).isInstanceOf(InvalidAdmissionTimeException.class);

        verifyNoInteractions(patientService, encounterRepository);
    }

    private Patient createPatient() {
        return new Patient(
                "MRN-200001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        );
    }
}