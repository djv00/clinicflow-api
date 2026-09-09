package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.EncounterDischarge;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterDischargeRepository extends JpaRepository<EncounterDischarge, UUID> {

    Optional<EncounterDischarge> findByEncounter_IdAndCancelledAtIsNull(UUID encounterId);

    @EntityGraph(attributePaths = {"encounter", "location", "restoredLocation"})
    List<EncounterDischarge> findAllByEncounter_IdOrderByDischargedAtAscIdAsc(UUID encounterId);
}
