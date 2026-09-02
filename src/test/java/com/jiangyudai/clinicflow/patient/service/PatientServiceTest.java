package com.jiangyudai.clinicflow.patient.service;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.exception.DuplicateMedicalRecordNumberException;
import com.jiangyudai.clinicflow.patient.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

        when(patientRepository.save(any(Patient.class)))
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

        verify(patientRepository).save(any(Patient.class));
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

        verify(patientRepository, never()).save(any(Patient.class));
    }
}
