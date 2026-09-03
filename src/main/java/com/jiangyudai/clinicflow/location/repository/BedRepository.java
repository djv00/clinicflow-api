package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Bed;
import org.springframework.data.jpa.repository.JpaRepository;

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
}