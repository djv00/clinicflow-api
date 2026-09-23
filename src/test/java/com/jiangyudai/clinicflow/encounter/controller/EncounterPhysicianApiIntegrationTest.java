package com.jiangyudai.clinicflow.encounter.controller;

import com.jiangyudai.clinicflow.encounter.dto.PhysicianAssignmentResponse;
import com.jiangyudai.clinicflow.encounter.service.EncounterPhysicianService;
import com.jiangyudai.clinicflow.encounter.service.EncounterService;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.service.PatientService;
import com.jiangyudai.clinicflow.physician.service.PhysicianService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:physician-assignment-api-it;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.open-in-view=false",
        "spring.security.user.name=Clinic.Operator",
        "spring.security.user.password=operator-password"
})
@AutoConfigureMockMvc
class EncounterPhysicianApiIntegrationTest {
    private static final OffsetDateTime ENTERED = OffsetDateTime.parse("2025-09-01T10:00:00-04:00");
    @Autowired private MockMvc mvc;
    @Autowired private JsonMapper mapper;
    @Autowired private EncounterService encounters;
    @Autowired private EncounterPhysicianService assignments;
    @Autowired private PhysicianService physicians;
    @Autowired private PatientService patients;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;
    private UUID departmentId;
    private UUID wardId;
    private UUID encounterId;
    private UUID locationId;
    private UUID physicianId;
    private UUID replacementId;

    @BeforeEach
    void setUp() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 16);
            var department = new Department("D-" + suffix, "Medicine");
            var ward = new Ward("W-" + suffix, "Medical ward");
            entityManager.persist(department);
            entityManager.persist(ward);
            departmentId = department.getId();
            wardId = ward.getId();
        });
        physicianId = physicians.create("PHY-" + UUID.randomUUID().toString().substring(0, 16),
                "Maya", "Chen", Set.of(departmentId)).id();
        replacementId = physicians.create("PHY-" + UUID.randomUUID().toString().substring(0, 16),
                "Alex", "Martin", Set.of(departmentId)).id();
        encounterId = admit();
        locationId = encounters.admitToDepartment(encounterId, departmentId, wardId, null, ENTERED).getId();
    }

    @Test
    void realLoginCanAssignHandOverAndReleaseWithSessionAuditAndFlatResponses() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "clinic.operator").param("password", "operator-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        var body = assignmentBody(physicianId, null, ENTERED);
        body.put("assignedBy", "forged");
        body.put("operator", "forged");
        var first = response(mvc.perform(post(path(encounterId)).session(session).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.assignedBy").value("Clinic.Operator"))
                .andExpect(jsonPath("$.physicianId").value(physicianId.toString()))
                .andExpect(jsonPath("$.physicianFirstName").value("Maya"))
                .andExpect(jsonPath("$.departmentName").value("Medicine"))
                .andExpect(jsonPath("$.endedAt").value(nullValue()))
                .andExpect(jsonPath("$.encounter").doesNotExist())
                .andExpect(jsonPath("$.physician").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        var next = response(mvc.perform(post(path(encounterId)).with(user("Handover.Operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(
                                assignmentBody(replacementId, first.id(), ENTERED.plusHours(1)))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        var release = releaseBody(ENTERED.plusHours(2));
        release.put("endedBy", "forged");
        release.put("endReason", "DISCHARGE");
        mvc.perform(post(releasePath(next.id())).session(session).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(release)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(next.id().toString()))
                .andExpect(jsonPath("$.endedBy").value("Clinic.Operator"))
                .andExpect(jsonPath("$.endReason").value("RELEASED"));
        mvc.perform(get(path(encounterId)).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.status").value("IN_DEPARTMENT"))
                .andExpect(jsonPath("$.currentLocation.id").value(locationId.toString()))
                .andExpect(jsonPath("$.currentAssignmentId").value(nullValue()))
                .andExpect(jsonPath("$.assignments.length()").value(2))
                .andExpect(jsonPath("$.assignments[0].id").value(first.id().toString()))
                .andExpect(jsonPath("$.assignments[0].endedBy").value("Handover.Operator"))
                .andExpect(jsonPath("$.assignments[0].endReason").value("REASSIGNED"))
                .andExpect(jsonPath("$.assignments[1].id").value(next.id().toString()))
                .andExpect(jsonPath("$.assignments[1].assignedBy").value("Handover.Operator"));
    }

    @Test
    void querySeparatesUnassignedStateFromOtherEncounterHistoryAndDischargeCorrections() throws Exception {
        UUID waiting = admit();
        UUID first = assignFixture();
        mvc.perform(get(path(waiting)).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.currentLocation").value(nullValue()))
                .andExpect(jsonPath("$.currentAssignmentId").value(nullValue()))
                .andExpect(jsonPath("$.assignments").isEmpty());
        mvc.perform(get(path(encounterId)).with(user("operator").roles("OPERATOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentAssignmentId").value(first.toString()))
                .andExpect(jsonPath("$.assignments.length()").value(1));
        encounters.dischargeEncounter(encounterId, ENTERED.plusHours(1), "discharge-clerk");
        mvc.perform(get(path(encounterId)).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISCHARGED"))
                .andExpect(jsonPath("$.currentLocation").value(nullValue()))
                .andExpect(jsonPath("$.currentAssignmentId").value(nullValue()))
                .andExpect(jsonPath("$.assignments[0].endReason").value("DISCHARGE"));
        encounters.cancelDischarge(encounterId, ENTERED.plusHours(2), "correction-clerk");
        var restored = encounters.getTimeline(encounterId).locations().getLast();
        mvc.perform(get(path(encounterId)).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.currentLocation.id").value(restored.id().toString()))
                .andExpect(jsonPath("$.currentAssignmentId").value(nullValue()))
                .andExpect(jsonPath("$.assignments[0].id").value(first.toString()))
                .andExpect(jsonPath("$.assignments[0].endedBy").value("discharge-clerk"));
    }

    @ParameterizedTest
    @CsvSource({"OPERATOR,200", "VIEWER,200", "ADMIN,403", "OTHER,403"})
    void appliesPatientReadPermissionsToAssignmentQueries(String role, int expectedStatus) throws Exception {
        mvc.perform(get(path(encounterId)).with(user("account").roles(role))).andExpect(status().is(expectedStatus));
        mvc.perform(head(path(encounterId)).with(user("account").roles(role))).andExpect(status().is(expectedStatus));
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "ADMIN", "OTHER"})
    void disallowedRolesCannotAssignOrReleaseEvenWithValidCsrf(String role) throws Exception {
        UUID id = assignFixture();
        for (String path : new String[]{path(encounterId), releasePath(id)}) {
            mvc.perform(post(path).with(user("account").roles(role)).with(csrf())
                            .contentType("application/json").content("{}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Access denied"));
        }
        assertThat(assignments.getAssignments(encounterId).currentAssignmentId()).isEqualTo(id);
    }

    @Test
    void anonymousRequestsCannotReadOrWriteAssignments() throws Exception {
        UUID id = assignFixture();
        mvc.perform(get(path(encounterId))).andExpect(status().isUnauthorized());
        for (String path : new String[]{path(encounterId), releasePath(id)}) {
            mvc.perform(post(path).with(csrf()).contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(assignments.getAssignments(encounterId).currentAssignmentId()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void csrfProtectionAppliesToBothWrites(boolean invalidToken) throws Exception {
        UUID id = assignFixture();
        for (String path : new String[]{path(encounterId), releasePath(id)}) {
            var request = post(path).with(user("operator").roles("OPERATOR")).contentType("application/json").content("{}");
            if (invalidToken) request.with(csrf().useInvalidToken());
            mvc.perform(request).andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Request not allowed"));
        }
        assertThat(assignments.getAssignments(encounterId).currentAssignmentId()).isEqualTo(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"physicianId", "expectedLocationId", "startedAt"})
    void requiredAssignmentFieldsAreValidated(String field) throws Exception {
        var body = assignmentBody(physicianId, null, ENTERED);
        body.remove(field);
        mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors." + field).exists());
        assertThat(assignments.getHistory(encounterId)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"physicianId,not-a-uuid", "expectedLocationId,not-a-uuid", "expectedAssignmentId,not-a-uuid",
            "startedAt,2025-09-01T10:00:00", "startedAt,not-a-date", "startedAt,2099-01-01T00:00:00Z"})
    void malformedAssignmentFieldsCannotWriteHistory(String field, String value) throws Exception {
        var body = assignmentBody(physicianId, null, ENTERED);
        body.put(field, value);
        mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(assignments.getHistory(encounterId)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"expectedLocationId", "endedAt", "local-time", "future"})
    void invalidReleaseRequestsLeaveResponsibilityOpen(String scenario) throws Exception {
        UUID id = assignFixture();
        var body = releaseBody(ENTERED.plusHours(1));
        if (scenario.equals("local-time")) body.put("endedAt", "2025-09-01T11:00:00");
        else if (scenario.equals("future")) body.put("endedAt", "2099-01-01T00:00:00Z");
        else body.put(scenario, null);
        mvc.perform(post(releasePath(id)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest());
        assertThat(assignments.getAssignments(encounterId).currentAssignmentId()).isEqualTo(id);
    }

    @Test
    void unavailablePhysicianAndUnknownReferencesReturnErrorsWithoutChangingAssignments() throws Exception {
        var profile = physicians.get(physicianId);
        physicians.changeActive(physicianId, false, profile.version());
        mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(assignmentBody(physicianId, null, ENTERED))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Invalid physician assignment"));
        mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(assignmentBody(UUID.randomUUID(), null, ENTERED))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Physician not found"));
        mvc.perform(get(path(UUID.randomUUID())).with(user("viewer").roles("VIEWER")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Encounter not found"));
        mvc.perform(post(path(UUID.randomUUID())).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(assignmentBody(replacementId, null, ENTERED))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/encounters/not-a-uuid/physician-assignments").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isBadRequest());
        assertThat(assignments.getHistory(encounterId)).isEmpty();
    }

    @Test
    void staleOrRepeatedRequestsCannotReplaceOrCloseLaterResponsibility() throws Exception {
        String originalBody = mapper.writeValueAsString(assignmentBody(physicianId, null, ENTERED));
        var first = response(mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(originalBody))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(originalBody))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Physician assignment conflict"));
        UUID next = assignments.assign(encounterId, replacementId, locationId, first.id(), ENTERED.plusHours(1), "other-operator").getId();
        mvc.perform(post(releasePath(first.id())).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(releaseBody(ENTERED.plusHours(2)))))
                .andExpect(status().isConflict());
        String releaseBody = mapper.writeValueAsString(releaseBody(ENTERED.plusHours(2)));
        mvc.perform(post(releasePath(next)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(releaseBody)).andExpect(status().isOk());
        mvc.perform(post(releasePath(next)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(releaseBody)).andExpect(status().isConflict());
        var state = assignments.getAssignments(encounterId);
        assertThat(state.currentAssignmentId()).isNull();
        assertThat(state.assignments()).hasSize(2);
        assertThat(state.assignments().getLast().endedBy()).isEqualTo("operator");
    }

    @Test
    void changedLocationAndDischargedStateRejectOldAssignmentForms() throws Exception {
        String request = mapper.writeValueAsString(assignmentBody(physicianId, null, ENTERED.plusHours(2)));
        UUID otherWard = new TransactionTemplate(transactionManager).execute(status -> {
            var ward = new Ward("W-" + UUID.randomUUID().toString().substring(0, 16), "Other ward");
            entityManager.persist(ward);
            return ward.getId();
        });
        encounters.transferEncounter(encounterId, departmentId, otherWard, null, ENTERED.plusHours(1), "transfer-clerk");
        mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(request))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Physician assignment conflict"));
        encounters.dischargeEncounter(encounterId, ENTERED.plusHours(3), "discharge-clerk");
        mvc.perform(post(path(encounterId)).with(user("operator").roles("OPERATOR")).with(csrf())
                        .contentType("application/json").content(request)).andExpect(status().isConflict());
        assertThat(assignments.getHistory(encounterId)).isEmpty();
    }

    private UUID assignFixture() {
        return assignments.assign(encounterId, physicianId, locationId, null, ENTERED, "fixture-operator").getId();
    }

    private Map<String, Object> assignmentBody(UUID physician, UUID expected, OffsetDateTime time) {
        var body = new LinkedHashMap<String, Object>();
        body.put("physicianId", physician);
        body.put("expectedLocationId", locationId);
        body.put("expectedAssignmentId", expected);
        body.put("startedAt", time);
        return body;
    }

    private Map<String, Object> releaseBody(OffsetDateTime time) {
        return new LinkedHashMap<>(Map.of("expectedLocationId", locationId, "endedAt", time));
    }

    private PhysicianAssignmentResponse response(String json) {
        return mapper.readValue(json, PhysicianAssignmentResponse.class);
    }

    private String releasePath(UUID id) {
        return path(encounterId) + "/" + id + "/releases";
    }

    private static String path(UUID id) {
        return "/api/v1/encounters/" + id + "/physician-assignments";
    }

    private UUID admit() {
        var patient = patients.createPatient("MRN-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 1, 1));
        return encounters.admitPatient(patient.getId(), "ENC-" + UUID.randomUUID(), ENTERED.minusHours(1)).getId();
    }
}
