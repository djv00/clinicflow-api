package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class EncounterRepositoryTest {

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private EncounterRepository encounterRepository;

    @Test
    void savesEncounterForPatient() {
        Patient patient = patientRepository.saveAndFlush(
                new Patient(
                        "MRN-200001",
                        "Maya",
                        "Chen",
                        LocalDate.of(1990, 5, 14)
                )
        );

        Encounter encounter = new Encounter(
                "ENC-2026-000001",
                patient,
                OffsetDateTime.parse("2026-09-02T16:30:00-04:00")
        );

        Encounter savedEncounter =
                encounterRepository.saveAndFlush(encounter);

        Encounter foundEncounter = encounterRepository
                .findById(savedEncounter.getId())
                .orElseThrow();

        assertThat(foundEncounter.getEncounterNumber())
                .isEqualTo("ENC-2026-000001");
        assertThat(foundEncounter.getPatient().getId())
                .isEqualTo(patient.getId());
        assertThat(foundEncounter.getStatus())
                .isEqualTo(EncounterStatus.ADMITTED);

        assertThat(encounterRepository.existsByPatient_IdAndStatusIn(
                patient.getId(),
                List.of(
                        EncounterStatus.ADMITTED,
                        EncounterStatus.IN_DEPARTMENT
                )
        )).isTrue();
    }
}