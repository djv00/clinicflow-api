package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import org.springframework.data.jpa.repository.JpaRepository;

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

    /**
     * Returns the complete location history in workflow order.
     */
    List<EncounterLocation>
    findAllByEncounter_IdOrderByStartedAtAsc(UUID encounterId);

    /**
     * Checks whether a bed is assigned to an open location.
     */
    boolean existsByBed_IdAndEndedAtIsNull(UUID bedId);

    /**
     * Checks whether another encounter currently occupies a bed.
     */
    boolean existsByBed_IdAndEndedAtIsNullAndEncounter_IdNot(
            UUID bedId,
            UUID encounterId
    );
}
