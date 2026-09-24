package com.jiangyudai.clinicflow.physician.controller;

import com.jiangyudai.clinicflow.physician.dto.*;
import com.jiangyudai.clinicflow.physician.service.PhysicianService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/physicians")
public class PhysicianController {
    private final PhysicianService physicians;

    public PhysicianController(PhysicianService physicians) {
        this.physicians = physicians;
    }

    @GetMapping
    public PhysicianPageResponse search(@Valid @ModelAttribute PhysicianSearchRequest request) {
        return physicians.search(request.keyword(), request.departmentId(), request.active(), request.page(), request.size());
    }

    @GetMapping("/{id}")
    public PhysicianResponse get(@PathVariable UUID id) {
        return physicians.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PhysicianResponse create(@Valid @RequestBody CreatePhysicianRequest request) {
        return physicians.create(request.physicianCode(), request.firstName(), request.lastName(), request.departmentIds());
    }

    @PutMapping("/{id}")
    public PhysicianResponse update(@PathVariable UUID id, @Valid @RequestBody UpdatePhysicianRequest request) {
        return physicians.update(id, request.firstName(), request.lastName(), request.departmentIds(), request.version());
    }

    @PutMapping("/{id}/active")
    public PhysicianResponse changeActive(@PathVariable UUID id, @Valid @RequestBody PhysicianActiveRequest request) {
        return physicians.changeActive(id, request.active(), request.version());
    }
}
