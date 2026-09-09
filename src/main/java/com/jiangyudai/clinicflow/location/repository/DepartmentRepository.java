package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository
        extends JpaRepository<Department, UUID> {

    Optional<Department> findByDepartmentCode(String departmentCode);

    boolean existsByDepartmentCode(String departmentCode);

    @Query("""
            select d from Department d
            where :active is null or d.active = :active
            order by d.departmentCode
            """)
    List<Department> findForLookup(@Param("active") Boolean active);
}
