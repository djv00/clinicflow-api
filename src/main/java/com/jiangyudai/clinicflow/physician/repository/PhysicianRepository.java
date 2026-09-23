package com.jiangyudai.clinicflow.physician.repository;

import com.jiangyudai.clinicflow.physician.entity.Physician;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PhysicianRepository extends JpaRepository<Physician, UUID> {

    Optional<Physician> findByPhysicianCode(String physicianCode);

    boolean existsByPhysicianCode(String physicianCode);
}
