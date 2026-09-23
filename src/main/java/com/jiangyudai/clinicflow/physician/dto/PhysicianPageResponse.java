package com.jiangyudai.clinicflow.physician.dto;

import java.util.List;

public record PhysicianPageResponse(List<PhysicianResponse> items, int page, int size,
                                    long totalElements, int totalPages) {
}
