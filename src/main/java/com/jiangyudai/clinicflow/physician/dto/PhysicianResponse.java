package com.jiangyudai.clinicflow.physician.dto;

import com.jiangyudai.clinicflow.location.dto.DepartmentResponse;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.physician.entity.Physician;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public record PhysicianResponse(UUID id, String physicianCode, String firstName, String lastName,
                                boolean active, long version, List<DepartmentResponse> departments) {
    public static PhysicianResponse from(Physician physician) {
        return new PhysicianResponse(physician.getId(), physician.getPhysicianCode(),
                physician.getFirstName(), physician.getLastName(), physician.isActive(), physician.getVersion(),
                physician.getDepartments().stream().sorted(Comparator.comparing(Department::getDepartmentCode))
                        .map(DepartmentResponse::from).toList());
    }
}
