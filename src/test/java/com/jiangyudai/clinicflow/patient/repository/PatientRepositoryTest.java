package com.jiangyudai.clinicflow.patient.repository;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PatientRepositoryTest {

    @Autowired
    private PatientRepository patientRepository;

    @Test
    void savesAndFindsPatient() {
        Patient patient = new Patient(
                "MRN-100001",
                "Maya",
                "Chen",
                LocalDate.of(1990, 5, 14)
        );

        Patient savedPatient = patientRepository.saveAndFlush(patient);

        Patient foundPatient = patientRepository
                .findById(savedPatient.getId())
                .orElseThrow();

        assertThat(foundPatient.getMedicalRecordNumber())
                .isEqualTo("MRN-100001");
    }
}
