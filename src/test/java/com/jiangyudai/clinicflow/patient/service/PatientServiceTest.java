package com.jiangyudai.clinicflow.patient.service;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.exception.DuplicateMedicalRecordNumberException;
import com.jiangyudai.clinicflow.patient.repository.PatientRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.SQLException;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PatientServiceTest {

    @Mock
    private PatientRepository patientRepository;

    @InjectMocks
    private PatientService patientService;

    @Test
    void createsPatientWhenMedicalRecordNumberIsAvailable() {
        when(patientRepository.existsByMedicalRecordNumber("MRN-100001"))
                .thenReturn(false);

        when(patientRepository.saveAndFlush(any(Patient.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Patient patient = patientService.createPatient(
                "MRN-100001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        );

        assertThat(patient.getMedicalRecordNumber())
                .isEqualTo("MRN-100001");
        assertThat(patient.getFirstName()).isEqualTo("Maya");

        verify(patientRepository).saveAndFlush(any(Patient.class));
    }

    @Test
    void rejectsDuplicateMedicalRecordNumber() {
        when(patientRepository.existsByMedicalRecordNumber("MRN-100001"))
                .thenReturn(true);

        assertThatThrownBy(() -> patientService.createPatient(
                "MRN-100001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        ))
                .isInstanceOf(DuplicateMedicalRecordNumberException.class)
                .hasMessageContaining("MRN-100001");

        verify(patientRepository, never()).saveAndFlush(any(Patient.class));
    }

    @Test
    void translatesConcurrentMedicalRecordNumberConflict() {
        var failure = new DataIntegrityViolationException("duplicate", new ConstraintViolationException(
                "duplicate", new SQLException("duplicate", "23505"), "uk_patients_medical_record_number"));
        when(patientRepository.saveAndFlush(any(Patient.class))).thenThrow(failure);

        assertThatThrownBy(() -> patientService.createPatient("MRN-RACE", "Maya", "Chen", LocalDate.of(1990, 5, 14)))
                .isInstanceOf(DuplicateMedicalRecordNumberException.class).hasMessageContaining("MRN-RACE");
    }

    @Test
    void preservesUnrelatedPersistenceFailure() {
        var failure = new DataIntegrityViolationException("invalid data", new ConstraintViolationException(
                "invalid data", new SQLException("invalid data", "23514"), "other_constraint"));
        when(patientRepository.saveAndFlush(any(Patient.class))).thenThrow(failure);

        assertThatThrownBy(() -> patientService.createPatient("MRN-100001", "Maya", "Chen", LocalDate.of(1990, 5, 14)))
                .isSameAs(failure);
    }
}
