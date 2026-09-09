package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.dto.BedResponse;
import com.jiangyudai.clinicflow.location.entity.Bed;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BedRepository extends JpaRepository<Bed, UUID> {

    Optional<Bed> findByWard_IdAndBedNumber(
            UUID wardId,
            String bedNumber
    );

    boolean existsByWard_IdAndBedNumber(
            UUID wardId,
            String bedNumber
    );

    // Read dictionary fields and open occupancy in one query; this does not reserve a bed.
    @Query("""
            select new com.jiangyudai.clinicflow.location.dto.BedResponse(
                b.id, b.bedNumber, b.ward.id, b.active,
                exists (select l.id from EncounterLocation l where l.bed = b and l.endedAt is null)
            )
            from Bed b
            where (:bedId is null or b.id = :bedId)
              and (:wardId is null or b.ward.id = :wardId)
              and (:active is null or b.active = :active)
              and (:occupied is null or
                case when exists (select l.id from EncounterLocation l where l.bed = b and l.endedAt is null)
                     then true else false end = :occupied)
            order by b.ward.wardCode, b.bedNumber, b.id
            """)
    List<BedResponse> findForLookup(
            @Param("bedId") UUID bedId,
            @Param("wardId") UUID wardId,
            @Param("active") Boolean active,
            @Param("occupied") Boolean occupied
    );

    /**
     * Loads a bed with a write lock before its current occupancy is checked.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Bed b where b.id = :id")
    Optional<Bed> findByIdForUpdate(@Param("id") UUID id);
}
