package com.jiangyudai.clinicflow.patient.dto;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import org.springframework.data.domain.Page;

import java.util.List;

public record PatientPageResponse(
        List<PatientResponse> items,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public static PatientPageResponse from(Page<Patient> patients) {
        return new PatientPageResponse(
                patients.getContent().stream().map(PatientResponse::from).toList(),
                patients.getNumber(), patients.getSize(),
                patients.getTotalElements(), patients.getTotalPages()
        );
    }
}
