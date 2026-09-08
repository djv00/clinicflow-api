package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface EncounterRepository
        extends JpaRepository<Encounter, UUID> {

    boolean existsByEncounterNumber(String encounterNumber);

    // Read only the ID so the encounter is not cached before acquiring workflow locks.
    @Query("select e.patient.id from Encounter e where e.id = :id")
    Optional<UUID> findPatientIdById(@Param("id") UUID id);

    boolean existsByPatient_IdAndStatusIn(
            UUID patientId,
            Collection<EncounterStatus> statuses
    );

    /**
     * Loads an encounter with a write lock for a state-changing workflow.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Encounter e where e.id = :id")
    Optional<Encounter> findByIdForUpdate(@Param("id") UUID id);
}
