package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Department;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository
        extends JpaRepository<Department, UUID> {

    Optional<Department> findByDepartmentCode(String departmentCode);

    boolean existsByDepartmentCode(String departmentCode);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select d from Department d where d.id = :id")
    Optional<Department> findByIdForRead(@Param("id") UUID id);

    @Query("""
            select d from Department d
            where :active is null or d.active = :active
            order by d.departmentCode
            """)
    List<Department> findForLookup(@Param("active") Boolean active);
}
