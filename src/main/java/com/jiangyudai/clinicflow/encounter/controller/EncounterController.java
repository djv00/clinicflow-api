package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.dto.AdmitPatientRequest;
import com.jiangyudai.clinicflow.encounter.dto.AdmitToDepartmentRequest;
import com.jiangyudai.clinicflow.encounter.dto.CancelAdmissionRequest;
import com.jiangyudai.clinicflow.encounter.dto.DischargeEncounterRequest;
import com.jiangyudai.clinicflow.encounter.dto.EncounterLocationResponse;
import com.jiangyudai.clinicflow.encounter.dto.EncounterResponse;
import com.jiangyudai.clinicflow.encounter.dto.TransferEncounterRequest;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
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

/**
 * REST endpoints for hospital encounters and location workflows.
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

    @PostMapping("/{id}/admission-cancellations")
    public EncounterResponse cancelAdmission(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody CancelAdmissionRequest request
    ) {
        Encounter encounter = encounterService.cancelAdmission(
                encounterId,
                request.cancelledAt(),
                request.cancelledBy()
        );

        return EncounterResponse.from(encounter);
    }

    @PostMapping("/{id}/discharges")
    public EncounterResponse dischargeEncounter(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody DischargeEncounterRequest request
    ) {
        Encounter encounter = encounterService.dischargeEncounter(
                encounterId,
                request.dischargedAt()
        );

        return EncounterResponse.from(encounter);
    }

    @PostMapping("/{id}/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterLocationResponse transferEncounter(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody TransferEncounterRequest request
    ) {
        EncounterLocation location =
                encounterService.transferEncounter(
                        encounterId,
                        request.departmentId(),
                        request.wardId(),
                        request.bedId(),
                        request.transferredAt()
                );

        return EncounterLocationResponse.from(location);
    }

}
