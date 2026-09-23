package com.jiangyudai.clinicflow.physician.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record PhysicianActiveRequest(
        @NotNull Boolean active,
        @NotNull @PositiveOrZero Long version
) {
}
