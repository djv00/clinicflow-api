package com.jiangyudai.clinicflow.location.controller;

import com.jiangyudai.clinicflow.location.dto.BedResponse;
import com.jiangyudai.clinicflow.location.dto.DepartmentResponse;
import com.jiangyudai.clinicflow.location.dto.WardResponse;
import com.jiangyudai.clinicflow.location.service.LocationQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class LocationController {

    private final LocationQueryService locationQueryService;

    public LocationController(LocationQueryService locationQueryService) {
        this.locationQueryService = locationQueryService;
    }

    @GetMapping("/departments")
    public List<DepartmentResponse> getDepartments(@RequestParam(required = false) Boolean active) {
        return locationQueryService.getDepartments(active);
    }

    @GetMapping("/departments/{id}")
    public DepartmentResponse getDepartment(@PathVariable UUID id) {
        return locationQueryService.getDepartment(id);
    }

    @GetMapping("/wards")
    public List<WardResponse> getWards(@RequestParam(required = false) Boolean active) {
        return locationQueryService.getWards(active);
    }

    @GetMapping("/wards/{id}")
    public WardResponse getWard(@PathVariable UUID id) {
        return locationQueryService.getWard(id);
    }

    @GetMapping("/beds")
    public List<BedResponse> getBeds(@RequestParam(required = false) UUID wardId,
                                     @RequestParam(required = false) Boolean active,
                                     @RequestParam(required = false) Boolean occupied) {
        return locationQueryService.getBeds(wardId, active, occupied);
    }

    @GetMapping("/beds/{id}")
    public BedResponse getBed(@PathVariable UUID id) {
        return locationQueryService.getBed(id);
    }
}
