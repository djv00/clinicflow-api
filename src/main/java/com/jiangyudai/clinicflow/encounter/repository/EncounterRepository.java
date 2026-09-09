package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
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

    boolean existsByPatient_IdAndStatusAndDischargedAtAfter(
            UUID patientId,
            EncounterStatus status,
            OffsetDateTime admittedAt
    );

    @Query("""
            select count(e) > 0 from Encounter e
            where e.patient.id = :patientId and e.id <> :encounterId
              and e.status <> :cancelledStatus
              and (e.admittedAt >= :dischargedAt or e.dischargedAt > :dischargedAt)
            """)
    boolean existsConflictingEncounterAfterDischarge(
            @Param("patientId") UUID patientId,
            @Param("encounterId") UUID encounterId,
            @Param("dischargedAt") OffsetDateTime dischargedAt,
            @Param("cancelledStatus") EncounterStatus cancelledStatus
    );

    /**
     * Keeps workflow changes from interleaving with a multi-query timeline read.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select e from Encounter e where e.id = :id")
    Optional<Encounter> findByIdForRead(@Param("id") UUID id);

    /**
     * Loads an encounter with a write lock for a state-changing workflow.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Encounter e where e.id = :id")
    Optional<Encounter> findByIdForUpdate(@Param("id") UUID id);
}
