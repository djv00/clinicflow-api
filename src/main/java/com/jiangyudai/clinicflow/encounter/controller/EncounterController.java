package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.service.EncounterQueryService;
import com.jiangyudai.clinicflow.encounter.dto.AdmitPatientRequest;
import com.jiangyudai.clinicflow.encounter.dto.AdmitToDepartmentRequest;
import com.jiangyudai.clinicflow.encounter.dto.CancelAdmissionRequest;
import com.jiangyudai.clinicflow.encounter.dto.CancelDischargeRequest;
import com.jiangyudai.clinicflow.encounter.dto.DischargeEncounterRequest;
import com.jiangyudai.clinicflow.encounter.dto.EncounterDischargeResponse;
import com.jiangyudai.clinicflow.encounter.dto.EncounterLocationResponse;
import com.jiangyudai.clinicflow.encounter.dto.EncounterResponse;
import com.jiangyudai.clinicflow.encounter.dto.EncounterTimelineResponse;
import com.jiangyudai.clinicflow.encounter.dto.TransferEncounterRequest;
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

import java.security.Principal;
import java.util.List;
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

    private final EncounterQueryService encounterQueries;

    public EncounterController(EncounterService encounterService, EncounterQueryService encounterQueries) {
        this.encounterQueries = encounterQueries;
        this.encounterService = encounterService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterResponse admitPatient(
            @Valid @RequestBody AdmitPatientRequest request
    ) {
        return encounterService.admitPatient(
                request.patientId(),
                request.encounterNumber(),
                request.admittedAt()
        );
    }

    @GetMapping("/{id}")
    public EncounterResponse getEncounter(@PathVariable UUID id) {
        return encounterQueries.getEncounter(id);
    }

    @GetMapping("/{id}/timeline")
    public EncounterTimelineResponse getTimeline(@PathVariable("id") UUID encounterId) {
        return encounterQueries.getTimeline(encounterId);
    }

    @PostMapping("/{id}/department-admissions")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterLocationResponse admitToDepartment(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody AdmitToDepartmentRequest request
    ) {
        return encounterService.admitToDepartment(
                encounterId,
                request.departmentId(),
                request.wardId(),
                request.bedId(),
                request.startedAt()
        );
    }

    @PostMapping("/{id}/admission-cancellations")
    public EncounterResponse cancelAdmission(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody CancelAdmissionRequest request,
            Principal principal
    ) {
        return encounterService.cancelAdmission(
                encounterId,
                request.cancelledAt(),
                principal.getName()
        );
    }

    @PostMapping("/{id}/discharges")
    public EncounterResponse dischargeEncounter(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody DischargeEncounterRequest request,
            Principal principal
    ) {
        return encounterService.dischargeEncounter(
                encounterId,
                request.dischargedAt(),
                principal.getName(),
                request.expectedLocationId()
        );
    }

    @PostMapping("/{id}/discharge-cancellations")
    public EncounterResponse cancelDischarge(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody CancelDischargeRequest request,
            Principal principal
    ) {
        return encounterService.cancelDischarge(
                encounterId, request.cancelledAt(), principal.getName(), request.expectedDischargeId()
        );
    }

    @GetMapping("/{id}/discharges")
    public List<EncounterDischargeResponse> getDischarges(@PathVariable("id") UUID encounterId) {
        return encounterQueries.getDischarges(encounterId);
    }

    @PostMapping("/{id}/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterLocationResponse transferEncounter(
            @PathVariable("id") UUID encounterId,
            @Valid @RequestBody TransferEncounterRequest request,
            Principal principal
    ) {
        return encounterService.transferEncounter(
                encounterId,
                request.departmentId(),
                request.wardId(),
                request.bedId(),
                request.transferredAt(),
                principal.getName(),
                request.expectedLocationId()
        );
    }

}
