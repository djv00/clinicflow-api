package com.jiangyudai.clinicflow.patient.controller;

import com.jiangyudai.clinicflow.patient.entity.Patient;
import com.jiangyudai.clinicflow.patient.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:patient-search-it;DB_CLOSE_ON_EXIT=FALSE")
@AutoConfigureMockMvc
@Transactional
class PatientSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PatientRepository patientRepository;

    private Patient firstPatient;

    @BeforeEach
    void setUp() {
        save("MRN-101", "Maya", "Chen");
        save("MRN-300", "Alex", "Wong");
        firstPatient = save("MRN-200", "Zoe", "Adams");
        save("MRN-100", "Maya", "Chen");
        patientRepository.flush();
    }

    @Test
    void listsPatientsWithDefaultsAndStableNameOrdering() throws Exception {
        mockMvc.perform(get("/api/v1/patients"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(4))
                .andExpect(jsonPath("$.items[0].id").value(firstPatient.getId().toString()))
                .andExpect(jsonPath("$.items[0].firstName").value("Zoe"))
                .andExpect(jsonPath("$.items[0].lastName").value("Adams"))
                .andExpect(jsonPath("$.items[0].dateOfBirth").value("1990-05-14"))
                .andExpect(jsonPath("$.items[1].medicalRecordNumber").value("MRN-100"))
                .andExpect(jsonPath("$.items[2].medicalRecordNumber").value("MRN-101"))
                .andExpect(jsonPath("$.items[3].medicalRecordNumber").value("MRN-300"))
                .andExpect(jsonPath("$.pageable").doesNotExist());
    }

    @Test
    void returnsDistinctPagesAndPreservesTotalsBeyondTheLastPage() throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].medicalRecordNumber").value("MRN-200"))
                .andExpect(jsonPath("$.items[1].medicalRecordNumber").value("MRN-100"))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/v1/patients").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].medicalRecordNumber").value("MRN-101"))
                .andExpect(jsonPath("$.items[1].medicalRecordNumber").value("MRN-300"));
        mockMvc.perform(get("/api/v1/patients").param("page", "2").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.totalElements").value(4))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"maya", "CHEN", "mAyA cHeN", "  maya chen  ", "aya ch", "mrn-10"})
    void findsCaseInsensitiveNameAndRecordNumberFragments(String keyword) throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].medicalRecordNumber").value("MRN-100"))
                .andExpect(jsonPath("$.items[1].medicalRecordNumber").value("MRN-101"));
    }

    @Test
    void countsOnlySearchMatchesWhenPaginating() throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("keyword", "Chen")
                        .param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].medicalRecordNumber").value("MRN-101"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void treatsBlankKeywordAsAnUnfilteredList(String keyword) throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(4));
    }

    @Test
    void returnsAnEmptyPageWhenNothingMatches() throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("keyword", "unknown-patient"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(0))
                .andExpect(jsonPath("$.totalPages").value(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"%", "_", "!", "\\"})
    void treatsLikeMetacharactersAndBackslashLiterally(String keyword) throws Exception {
        Patient literal = save("SPECIAL" + keyword, "Test", "Literal");
        save("SPECIALX", "Test", "Control");
        mockMvc.perform(get("/api/v1/patients").param("keyword", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(literal.getId().toString()));
    }

    @Test
    void searchesNamesContainingAnApostrophe() throws Exception {
        Patient patient = save("MRN-400", "Anne", "O'Neil");
        mockMvc.perform(get("/api/v1/patients").param("keyword", "o'neil"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(patient.getId().toString()));
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101", "page,abc", "size,abc", "page,2147483648"})
    void rejectsInvalidPagination(String parameter, String value) throws Exception {
        mockMvc.perform(get("/api/v1/patients").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.errors." + parameter).exists());
    }

    @Test
    void rejectsAnOffsetOutsideJpaRange() throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("page", "2147483647").param("size", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageOffsetValid").value("Requested page is too large"));
    }

    @Test
    void rejectsAnOverlongKeyword() throws Exception {
        mockMvc.perform(get("/api/v1/patients").param("keyword", "x".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.keyword").exists());
    }

    private Patient save(String recordNumber, String firstName, String lastName) {
        return patientRepository.save(new Patient(recordNumber, firstName, lastName, LocalDate.of(1990, 5, 14)));
    }
}
