package com.jiangyudai.clinicflow.encounter.dto;

import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import org.springframework.data.domain.Page;

import java.util.List;

public record EncounterPageResponse(
        List<EncounterResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static EncounterPageResponse from(Page<Encounter> encounters) {
        return new EncounterPageResponse(
                encounters.getContent().stream().map(EncounterResponse::from).toList(),
                encounters.getNumber(), encounters.getSize(),
                encounters.getTotalElements(), encounters.getTotalPages()
        );
    }
}
