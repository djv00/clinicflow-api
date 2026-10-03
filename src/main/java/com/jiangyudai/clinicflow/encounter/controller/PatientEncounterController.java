package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.dto.EncounterPageRequest;
import com.jiangyudai.clinicflow.encounter.dto.EncounterPageResponse;
import com.jiangyudai.clinicflow.encounter.service.EncounterQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/patients/{patientId}/encounters")
public class PatientEncounterController {

    private final EncounterQueryService encounterService;

    public PatientEncounterController(EncounterQueryService encounterService) {
        this.encounterService = encounterService;
    }

    @GetMapping
    public EncounterPageResponse getEncounters(
            @PathVariable UUID patientId,
            @Valid @ModelAttribute EncounterPageRequest request
    ) {
        return encounterService.getPatientEncounters(patientId, request.page(), request.size());
    }
}
