package com.jiangyudai.clinicflow.encounter.repository;

import com.jiangyudai.clinicflow.encounter.entity.EncounterPhysicianAssignment;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EncounterPhysicianAssignmentRepository extends JpaRepository<EncounterPhysicianAssignment, UUID> {
    @EntityGraph(attributePaths = {"physician", "department"})
    Optional<EncounterPhysicianAssignment> findByEncounter_IdAndEndedAtIsNull(UUID encounterId);

    // ID makes equal-time records deterministic for display; it does not imply operation order.
    @EntityGraph(attributePaths = {"physician", "department"})
    List<EncounterPhysicianAssignment> findAllByEncounter_IdOrderByStartedAtAscIdAsc(UUID encounterId);

    boolean existsByEncounter_IdAndEndedAtAfter(UUID encounterId, OffsetDateTime time);
}
