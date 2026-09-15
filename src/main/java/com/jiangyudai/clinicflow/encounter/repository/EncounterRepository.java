package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.dto.InpatientResponse;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    Page<Encounter> findAllByPatient_Id(UUID patientId, Pageable pageable);

    // Only the open location participates: previous wards must not match the worklist filter.
    @Query(value = """
            select new com.jiangyudai.clinicflow.encounter.dto.InpatientResponse(
                e.id, e.encounterNumber, e.status, e.admittedAt,
                p.id, p.firstName, p.lastName, p.medicalRecordNumber, p.dateOfBirth,
                d.id, d.departmentName, d.departmentCode, w.id, w.wardName, w.wardCode,
                b.id, b.bedNumber)
            from Encounter e join e.patient p
            left join EncounterLocation l on l.encounter = e and l.endedAt is null
            left join l.department d left join l.ward w left join l.bed b
            where e.status in :activeStatuses
              and (:status is null or e.status = :status)
              and (:departmentId is null or d.id = :departmentId)
              and (:wardId is null or w.id = :wardId)
              and (lower(e.encounterNumber) like lower(:pattern) escape '!'
                or lower(p.medicalRecordNumber) like lower(:pattern) escape '!'
                or lower(concat(p.firstName, ' ', p.lastName)) like lower(:pattern) escape '!')
            order by e.admittedAt, e.encounterNumber
            """, countQuery = """
            select count(e) from Encounter e join e.patient p
            left join EncounterLocation l on l.encounter = e and l.endedAt is null
            where e.status in :activeStatuses
              and (:status is null or e.status = :status)
              and (:departmentId is null or l.department.id = :departmentId)
              and (:wardId is null or l.ward.id = :wardId)
              and (lower(e.encounterNumber) like lower(:pattern) escape '!'
                or lower(p.medicalRecordNumber) like lower(:pattern) escape '!'
                or lower(concat(p.firstName, ' ', p.lastName)) like lower(:pattern) escape '!')
            """)
    Page<InpatientResponse> searchInpatients(
            @Param("activeStatuses") Collection<EncounterStatus> activeStatuses,
            @Param("status") EncounterStatus status,
            @Param("departmentId") UUID departmentId,
            @Param("wardId") UUID wardId,
            @Param("pattern") String pattern,
            Pageable pageable
    );

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
