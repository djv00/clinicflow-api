package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.dto.AdmitPatientRequest;
import com.jiangyudai.clinicflow.encounter.dto.AdmitToDepartmentRequest;
import com.jiangyudai.clinicflow.encounter.dto.EncounterLocationResponse;
import com.jiangyudai.clinicflow.encounter.dto.EncounterResponse;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * REST endpoints for hospital and department admission workflows.
 *
 * @author Jiangyu Dai
 */
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

    @PostMapping("/{id}/department-admissions")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterLocationResponse admitToDepartment(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody AdmitToDepartmentRequest request
    ) {
        EncounterLocation location = encounterService.admitToDepartment(
                encounterId,
                request.departmentId(),
                request.wardId(),
                request.bedId(),
                request.startedAt()
        );

        return EncounterLocationResponse.from(location);
    }
}
