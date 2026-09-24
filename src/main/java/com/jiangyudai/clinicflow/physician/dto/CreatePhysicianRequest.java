package com.jiangyudai.clinicflow.physician.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

public record CreatePhysicianRequest(
        @NotBlank @Size(max = 30) String physicianCode,
        @NotBlank @Size(max = 100) String firstName,
        @NotBlank @Size(max = 100) String lastName,
        @NotNull @Size(max = 100) Set<@NotNull UUID> departmentIds
) {
}
