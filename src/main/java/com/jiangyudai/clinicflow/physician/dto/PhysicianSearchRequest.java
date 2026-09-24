package com.jiangyudai.clinicflow.physician.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record PhysicianSearchRequest(
        @Size(max = 100) String keyword,
        UUID departmentId,
        Boolean active,
        @Min(0) Integer page,
        @Min(1) @Max(100) Integer size
) {
    public PhysicianSearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }

    @AssertTrue(message = "Requested page is too large")
    public boolean isPageOffsetValid() {
        return (long) page * size <= Integer.MAX_VALUE;
    }
}
