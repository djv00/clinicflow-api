package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterLocationRepository
        extends JpaRepository<EncounterLocation, UUID> {

    /**
     * Returns the current location; an open location has no end time.
     */
    Optional<EncounterLocation>
    findByEncounter_IdAndEndedAtIsNull(UUID encounterId);

    boolean existsByEncounter_IdAndEndedAtIsNull(UUID encounterId);

    boolean existsByEncounter_Id(UUID encounterId);

    /**
     * Returns the complete location history in workflow order.
     */
    List<EncounterLocation>
    findAllByEncounter_IdOrderByStartedAtAsc(UUID encounterId);

    // ID breaks timestamp ties for display; it does not establish operation order.
    List<EncounterLocation> findAllByEncounter_IdOrderByStartedAtAscIdAsc(UUID encounterId);

    /**
     * Checks whether a bed is assigned to an open location.
     */
    boolean existsByBed_IdAndEndedAtIsNull(UUID bedId);

    // A new open interval overlaps any non-empty closed interval ending after its start.
    @Query("""
            select count(l) > 0 from EncounterLocation l
            where l.bed.id = :bedId and l.endedAt > :startedAt
              and l.startedAt < l.endedAt
            """)
    boolean existsClosedBedHistoryAfter(
            @Param("bedId") UUID bedId,
            @Param("startedAt") OffsetDateTime startedAt
    );

    /**
     * Checks whether another encounter currently occupies a bed.
     */
    boolean existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
            UUID bedId,
            UUID encounterId
    );
}
