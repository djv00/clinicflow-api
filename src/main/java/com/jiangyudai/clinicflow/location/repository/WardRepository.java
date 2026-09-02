package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface WardRepository extends JpaRepository<Ward, UUID> {

    Optional<Ward> findByWardCode(String wardCode);

    boolean existsByWardCode(String wardCode);
}