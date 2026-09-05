package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.location.repository.BedRepository;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.location.repository.WardRepository;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.repository.PatientRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class EncounterLocationRepositoryTest {

    @Autowired
    private PatientRepository patientRepository;

    @Autowired
    private EncounterRepository encounterRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private WardRepository wardRepository;

    @Autowired
    private BedRepository bedRepository;

    @Autowired
    private EncounterLocationRepository encounterLocationRepository;

    @Test
    void savesCurrentEncounterLocation() {
        Patient patient = patientRepository.saveAndFlush(
                new Patient(
                        "MRN-300001",
                        "Maya",
                        "Chen",
                        LocalDate.of(1990, 5, 14)
                )
        );

        Encounter encounter = encounterRepository.saveAndFlush(
                new Encounter(
                        "ENC-2026-000002",
                        patient,
                        OffsetDateTime.parse(
                                "2026-09-01T14:00:00-04:00"
                        )
                )
        );

        Department department = departmentRepository.saveAndFlush(
                new Department("CARD", "Cardiology")
        );

        Ward ward = wardRepository.saveAndFlush(
                new Ward("WARD-A", "General Inpatient Ward")
        );

        Bed bed = bedRepository.saveAndFlush(
                new Bed("01", ward)
        );

        encounterLocationRepository.saveAndFlush(
                new EncounterLocation(
                        encounter,
                        department,
                        ward,
                        bed,
                        OffsetDateTime.parse(
                                "2026-09-01T15:00:00-04:00"
                        )
                )
        );

        EncounterLocation location = encounterLocationRepository
                .findByEncounter_IdAndEndedAtIsNull(encounter.getId())
                .orElseThrow();

        assertThat(location.getDepartment().getId())
                .isEqualTo(department.getId());
        assertThat(location.getWard().getId())
                .isEqualTo(ward.getId());
        assertThat(location.getBed().getId())
                .isEqualTo(bed.getId());
        assertThat(location.getEndedAt()).isNull();
    }
}