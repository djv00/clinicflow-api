package com.jiangyudai.clinicflow.patient.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record PatientSearchRequest(
        @Size(max = 100, message = "Search keyword must not exceed 100 characters")
        String keyword,

        @Min(value = 0, message = "Page must not be negative")
        Integer page,

        @Min(value = 1, message = "Page size must be at least 1")
        @Max(value = 100, message = "Page size must not exceed 100")
        Integer size
) {
    public PatientSearchRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
    }

    // JPA uses an int for the first result offset.
    @AssertTrue(message = "Requested page is too large")
    public boolean isPageOffsetValid() {
        return (long) page * size <= Integer.MAX_VALUE;
    }
}
