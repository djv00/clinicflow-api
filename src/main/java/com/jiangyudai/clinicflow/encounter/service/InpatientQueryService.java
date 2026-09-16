package com.jiangyudai.clinicflow.encounter.service;

import com.jiangyudai.clinicflow.encounter.dto.InpatientPageResponse;
import com.jiangyudai.clinicflow.encounter.dto.InpatientSearchRequest;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.encounter.repository.EncounterRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class InpatientQueryService {
    private final EncounterRepository encounterRepository;

    public InpatientQueryService(EncounterRepository encounterRepository) {
        this.encounterRepository = encounterRepository;
    }

    public InpatientPageResponse searchInpatients(InpatientSearchRequest search, int page, int size) {
        String keyword = search.keyword() == null ? "" : search.keyword().strip();
        // Search literal fragments, including identifiers containing LIKE metacharacters.
        String pattern = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        return InpatientPageResponse.from(encounterRepository.searchInpatients(
                List.of(EncounterStatus.ADMITTED, EncounterStatus.IN_DEPARTMENT), search.status(),
                search.departmentId(), search.wardId(), pattern, PageRequest.of(page, size)));
    }
}
