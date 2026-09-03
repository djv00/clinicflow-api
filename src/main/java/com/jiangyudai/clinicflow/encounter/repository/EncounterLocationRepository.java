package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterLocationRepository
        extends JpaRepository<EncounterLocation, UUID> {

    Optional<EncounterLocation>
    findByEncounter_IdAndEndedAtIsNull(UUID encounterId);

    boolean existsByEncounter_IdAndEndedAtIsNull(UUID encounterId);

    List<EncounterLocation>
    findAllByEncounter_IdOrderByStartedAtAsc(UUID encounterId);
}