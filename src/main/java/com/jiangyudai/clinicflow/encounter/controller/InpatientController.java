package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.dto.EncounterPageRequest;
import com.jiangyudai.clinicflow.encounter.dto.InpatientPageResponse;
import com.jiangyudai.clinicflow.encounter.dto.InpatientSearchRequest;
import com.jiangyudai.clinicflow.encounter.service.InpatientQueryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/inpatients")
public class InpatientController {
    private final InpatientQueryService inpatientQueryService;

    public InpatientController(InpatientQueryService inpatientQueryService) {
        this.inpatientQueryService = inpatientQueryService;
    }

    @GetMapping
    public InpatientPageResponse getInpatients(
            @Valid @ModelAttribute InpatientSearchRequest search,
            @Valid @ModelAttribute EncounterPageRequest pagination
    ) {
        return inpatientQueryService.searchInpatients(search, pagination.page(), pagination.size());
    }
}
