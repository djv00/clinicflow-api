package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Ward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WardRepository extends JpaRepository<Ward, UUID> {

    Optional<Ward> findByWardCode(String wardCode);

    boolean existsByWardCode(String wardCode);

    @Query("""
            select w from Ward w
            where :active is null or w.active = :active
            order by w.wardCode
            """)
    List<Ward> findForLookup(@Param("active") Boolean active);
}
