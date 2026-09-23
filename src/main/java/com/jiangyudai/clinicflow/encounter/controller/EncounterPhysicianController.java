package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.dto.AssignPhysicianRequest;
import com.jiangyudai.clinicflow.encounter.dto.EncounterPhysicianAssignmentsResponse;
import com.jiangyudai.clinicflow.encounter.dto.PhysicianAssignmentResponse;
import com.jiangyudai.clinicflow.encounter.dto.ReleasePhysicianRequest;
import com.jiangyudai.clinicflow.encounter.service.EncounterPhysicianService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/encounters/{encounterId}/physician-assignments")
public class EncounterPhysicianController {
    private final EncounterPhysicianService physicians;

    public EncounterPhysicianController(EncounterPhysicianService physicians) {
        this.physicians = physicians;
    }

    @GetMapping
    public EncounterPhysicianAssignmentsResponse get(@PathVariable UUID encounterId) {
        return physicians.getAssignments(encounterId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PhysicianAssignmentResponse assign(@PathVariable UUID encounterId,
                                              @Valid @RequestBody AssignPhysicianRequest request, Principal principal) {
        return PhysicianAssignmentResponse.from(physicians.assign(encounterId, request.physicianId(),
                request.expectedLocationId(), request.expectedAssignmentId(), request.startedAt(), principal.getName()));
    }

    @PostMapping("/{assignmentId}/releases")
    public PhysicianAssignmentResponse release(@PathVariable UUID encounterId, @PathVariable UUID assignmentId,
                                               @Valid @RequestBody ReleasePhysicianRequest request, Principal principal) {
        return PhysicianAssignmentResponse.from(physicians.release(encounterId, request.expectedLocationId(),
                assignmentId, request.endedAt(), principal.getName()));
    }
}
