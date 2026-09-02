package com.jiangyudai.clinicflow.location.repository;

import com.jiangyudai.clinicflow.location.entity.Department;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DepartmentRepository
        extends JpaRepository<Department, UUID> {

    Optional<Department> findByDepartmentCode(String departmentCode);

    boolean existsByDepartmentCode(String departmentCode);
}