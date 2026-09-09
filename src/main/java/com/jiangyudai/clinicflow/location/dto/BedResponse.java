package com.jiangyudai.clinicflow.location.dto;

import java.util.UUID;

public record BedResponse(UUID id, String bedNumber, UUID wardId, boolean active, boolean occupied) {
}
