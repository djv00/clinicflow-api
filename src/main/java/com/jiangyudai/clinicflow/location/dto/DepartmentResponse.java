package com.jiangyudai.clinicflow.location.dto;

import com.jiangyudai.clinicflow.location.entity.Department;

import java.util.UUID;

public record DepartmentResponse(UUID id, String departmentCode, String departmentName, boolean active) {

    public static DepartmentResponse from(Department department) {
        return new DepartmentResponse(department.getId(), department.getDepartmentCode(),
                department.getDepartmentName(), department.isActive());
    }
}
