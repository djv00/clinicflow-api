package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.UUID;

public interface EncounterRepository
        extends JpaRepository<Encounter, UUID> {

    boolean existsByEncounterNumber(String encounterNumber);

    boolean existsByPatient_IdAndStatusIn(
            UUID patientId,
            Collection<EncounterStatus> statuses
    );
}