package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.dto.AdmitPatientRequest;
import com.jiangyudai.clinicflow.encounter.dto.EncounterResponse;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/encounters")
public class EncounterController {

    private final EncounterService encounterService;

    public EncounterController(EncounterService encounterService) {
        this.encounterService = encounterService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterResponse admitPatient(
            @Valid @RequestBody AdmitPatientRequest request
    ) {
        Encounter encounter = encounterService.admitPatient(
                request.patientId(),
                request.encounterNumber(),
                request.admittedAt()
        );

        return EncounterResponse.from(encounter);
    }

    @GetMapping("/{id}")
    public EncounterResponse getEncounter(@PathVariable UUID id) {
        return EncounterResponse.from(encounterService.getEncounter(id));
    }
}
