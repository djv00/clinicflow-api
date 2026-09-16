package com.jiangyudai.clinicflow.encounter.dto;

import org.springframework.data.domain.Page;

import java.util.List;

public record InpatientPageResponse(
        List<InpatientResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static InpatientPageResponse from(Page<InpatientResponse> inpatients) {
        return new InpatientPageResponse(inpatients.getContent(), inpatients.getNumber(), inpatients.getSize(),
                inpatients.getTotalElements(), inpatients.getTotalPages());
    }
}
