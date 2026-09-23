package com.jiangyudai.clinicflow.physician.controller;

import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.repository.DepartmentRepository;
import com.jiangyudai.clinicflow.physician.dto.PhysicianResponse;
import com.jiangyudai.clinicflow.physician.repository.PhysicianRepository;
import com.jiangyudai.clinicflow.physician.service.PhysicianService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:physician-directory-it;DB_CLOSE_ON_EXIT=FALSE",
        "spring.security.user.password=operator-password",
        "clinicflow.security.admin.password=admin-password"
})
@AutoConfigureMockMvc
class PhysicianDirectoryIntegrationTest {
    @Autowired
    private MockMvc mvc;
    @Autowired
    private JsonMapper mapper;
    @Autowired
    private PhysicianService service;
    @Autowired
    private PhysicianRepository physicians;
    @Autowired
    private DepartmentRepository departments;
    @Autowired
    private JdbcTemplate jdbc;

    private Department medicine;
    private Department surgery;

    @BeforeEach
    void setUp() {
        physicians.deleteAll();
        departments.deleteAll();
        medicine = departments.saveAndFlush(new Department("MED", "Medicine"));
        surgery = departments.saveAndFlush(new Department("SURG", "Surgery"));
    }

    @Test
    void administratorCanCreateEditDeactivateAndReactivateUsingARealSession() throws Exception {
        var session = (MockHttpSession) mvc.perform(post("/api/auth/login").with(csrf())
                        .param("username", "admin").param("password", "admin-password"))
                .andExpect(status().isNoContent()).andReturn().getRequest().getSession(false);
        var created = mvc.perform(post("/api/v1/physicians").session(session).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                                "physicianCode", " PHY-001 ", "firstName", " Maya ", "lastName", "Chen",
                                "departmentIds", Set.of(medicine.getId(), surgery.getId())))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.physicianCode").value("PHY-001"))
                .andExpect(jsonPath("$.firstName").value("Maya"))
                .andExpect(jsonPath("$.departments[0].departmentCode").value("MED"))
                .andExpect(jsonPath("$.departments[1].departmentCode").value("SURG"))
                .andReturn().getResponse().getContentAsString();
        PhysicianResponse physician = mapper.readValue(created, PhysicianResponse.class);
        String updated = mvc.perform(put("/api/v1/physicians/{id}", physician.id()).session(session).with(csrf())
                        .contentType("application/json").content(editBody("Mai", Set.of(surgery.getId()), physician.version())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.firstName").value("Mai"))
                .andExpect(jsonPath("$.departments.length()").value(1))
                .andExpect(jsonPath("$.physicianCode").value("PHY-001"))
                .andReturn().getResponse().getContentAsString();
        PhysicianResponse edited = mapper.readValue(updated, PhysicianResponse.class);
        String inactive = mvc.perform(put("/api/v1/physicians/{id}/active", physician.id()).session(session).with(csrf())
                        .contentType("application/json").content(activeBody(false, edited.version())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false))
                .andExpect(jsonPath("$.departments[0].id").value(surgery.getId().toString()))
                .andReturn().getResponse().getContentAsString();
        PhysicianResponse deactivated = mapper.readValue(inactive, PhysicianResponse.class);
        mvc.perform(put("/api/v1/physicians/{id}/active", physician.id()).session(session).with(csrf())
                        .contentType("application/json").content(activeBody(true, deactivated.version())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(true));
        assertThat(departments.existsById(medicine.getId())).isTrue();
    }

    @Test
    void filtersAndPaginatesPhysiciansWithoutDuplicatingSharedAffiliations() throws Exception {
        PhysicianResponse first = service.create("PHY-001", "Maya", "Chen", Set.of(medicine.getId(), surgery.getId()));
        service.create("PHY-002", "Maya", "Chen", Set.of(medicine.getId()));
        PhysicianResponse third = service.create("PHY-003", "Alex", "Wong", Set.of(surgery.getId()));
        service.changeActive(third.id(), false, third.version());

        mvc.perform(get("/api/v1/physicians").with(user("viewer").roles("VIEWER"))
                        .param("departmentId", medicine.getId().toString()).param("active", "true")
                        .param("keyword", "  MAYA CHEN  ").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2)).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(first.id().toString()))
                .andExpect(jsonPath("$.items[0].departments.length()").value(2));
        mvc.perform(get("/api/v1/physicians").with(user("operator").roles("OPERATOR"))
                        .param("departmentId", medicine.getId().toString()).param("size", "1").param("page", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].physicianCode").value("PHY-002"));
        mvc.perform(get("/api/v1/physicians").with(user("viewer").roles("VIEWER"))
                        .param("active", "false"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value(third.id().toString()));
        mvc.perform(get("/api/v1/physicians").with(user("viewer").roles("VIEWER"))
                        .param("page", "10").param("size", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.totalElements").value(3));
    }

    @Test
    void treatsSearchMetacharactersAsLiteralText() throws Exception {
        service.create("PHY%_!", "Maya", "Chen", Set.of());
        service.create("PHY-001", "Alex", "Martin", Set.of());
        mvc.perform(get("/api/v1/physicians").with(user("viewer").roles("VIEWER")).param("keyword", "%_!"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].physicianCode").value("PHY%_!"));
    }

    @Test
    void rejectsStaleEditsAndStatusChangesWithoutOverwritingTheLatestAffiliations() throws Exception {
        PhysicianResponse original = service.create("PHY-001", "Maya", "Chen", Set.of(medicine.getId()));
        service.update(original.id(), "Maya", "Chen", Set.of(surgery.getId()), original.version());
        mvc.perform(put("/api/v1/physicians/{id}", original.id()).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(editBody("Stale", Set.of(medicine.getId()), original.version())))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Update conflict"));
        mvc.perform(put("/api/v1/physicians/{id}/active", original.id()).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(activeBody(false, original.version())))
                .andExpect(status().isConflict());
        var current = service.get(original.id());
        assertThat(current.firstName()).isEqualTo("Maya");
        assertThat(current.active()).isTrue();
        assertThat(current.departments()).extracting("id").containsExactly(surgery.getId());
    }

    @Test
    void invalidDepartmentReplacementLeavesProfileAndExistingAffiliationsUnchanged() throws Exception {
        var physician = service.create("PHY-001", "Maya", "Chen", Set.of(medicine.getId()));
        jdbc.update("UPDATE departments SET active = false WHERE id = ?", surgery.getId());
        mvc.perform(put("/api/v1/physicians/{id}", physician.id()).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(editBody("Changed", Set.of(surgery.getId()), physician.version())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.title").value("Invalid physician department"));
        mvc.perform(put("/api/v1/physicians/{id}", physician.id()).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(editBody("Changed", Set.of(UUID.randomUUID()), physician.version())))
                .andExpect(status().isNotFound());
        assertThat(service.get(physician.id())).isEqualTo(physician);
    }

    @Test
    void existingInactiveAffiliationCanBeRetainedOrRemovedButNotAddedBack() throws Exception {
        var physician = service.create("PHY-001", "Maya", "Chen", Set.of(medicine.getId()));
        jdbc.update("UPDATE departments SET active = false WHERE id = ?", medicine.getId());
        var renamed = service.update(physician.id(), "Mai", "Chen", Set.of(medicine.getId()), physician.version());
        assertThat(renamed.departments()).singleElement().satisfies(department -> assertThat(department.active()).isFalse());
        var removed = service.update(physician.id(), "Mai", "Chen", Set.of(), renamed.version());
        mvc.perform(put("/api/v1/physicians/{id}", physician.id()).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(editBody("Mai", Set.of(medicine.getId()), removed.version())))
                .andExpect(status().isBadRequest());
        assertThat(service.get(physician.id()).departments()).isEmpty();
    }

    @Test
    void duplicateCodeReturnsConflictWithoutAddingAnotherPhysician() throws Exception {
        service.create("PHY-001", "Maya", "Chen", Set.of());
        mvc.perform(post("/api/v1/physicians").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(mapper.writeValueAsString(Map.of(
                                "physicianCode", " PHY-001 ", "firstName", "Alex", "lastName", "Martin", "departmentIds", Set.of()))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.title").value("Duplicate physician code"));
        assertThat(physicians.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"VIEWER", "OPERATOR"})
    void clinicalRolesCanReadButCannotMaintainTheDirectory(String role) throws Exception {
        var physician = service.create("PHY-001", "Maya", "Chen", Set.of());
        mvc.perform(get("/api/v1/physicians/{id}", physician.id()).with(user("reader").roles(role)))
                .andExpect(status().isOk());
        mvc.perform(head("/api/v1/physicians/{id}", physician.id()).with(user("reader").roles(role)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/physicians").with(user("reader").roles(role)).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Access denied"));
        mvc.perform(put("/api/v1/physicians/{id}", physician.id()).with(user("reader").roles(role)).with(csrf())
                        .contentType("application/json").content(editBody("Changed", Set.of(), physician.version())))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/physicians/{id}/active", physician.id()).with(user("reader").roles(role)).with(csrf())
                        .contentType("application/json").content(activeBody(false, physician.version())))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/physicians/{id}", physician.id()).with(user("reader").roles(role)).with(csrf()))
                .andExpect(status().isForbidden());
        assertThat(service.get(physician.id())).isEqualTo(physician);
    }

    @Test
    void anonymousAndUnrecognisedRolesCannotReadDirectory() throws Exception {
        mvc.perform(get("/api/v1/physicians")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/physicians").with(user("other").roles("OTHER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/physicians").with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminWritesStillRequireCsrfAndNoDeleteEndpointIsExposed() throws Exception {
        var physician = service.create("PHY-001", "Maya", "Chen", Set.of());
        mvc.perform(post("/api/v1/physicians").with(user("admin").roles("ADMIN"))
                        .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.title").value("Request not allowed"));
        mvc.perform(delete("/api/v1/physicians/{id}", physician.id()).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isMethodNotAllowed());
        assertThat(physicians.existsById(physician.id())).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"active\":true}", "{\"active\":false,\"version\":-1}", "{\"version\":0}"})
    void rejectsIncompleteOrInvalidStatusRequests(String body) throws Exception {
        mvc.perform(put("/api/v1/physicians/{id}/active", UUID.randomUUID()).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors").exists());
    }

    @ParameterizedTest
    @CsvSource({"page,-1", "size,0", "size,101", "page,2147483647", "departmentId,invalid", "active,invalid"})
    void rejectsInvalidSearchParameters(String name, String value) throws Exception {
        mvc.perform(get("/api/v1/physicians").with(user("viewer").roles("VIEWER")).param(name, value))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsMissingNamesDepartmentsAndVersionOnProfileRequests() throws Exception {
        mvc.perform(post("/api/v1/physicians").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.departmentIds").exists());
        mvc.perform(put("/api/v1/physicians/{id}", UUID.randomUUID()).with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.version").exists());
        mvc.perform(post("/api/v1/physicians").with(user("admin").roles("ADMIN")).with(csrf())
                        .contentType("application/json").content("""
                                {"physicianCode":"PHY-001","firstName":"Maya","lastName":"Chen","departmentIds":[null]}
                                """))
                .andExpect(status().isBadRequest());
        assertThat(physicians.count()).isZero();
    }

    @Test
    void missingPhysicianReturnsNotFound() throws Exception {
        mvc.perform(get("/api/v1/physicians/{id}", UUID.randomUUID()).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.title").value("Physician not found"));
    }

    private String editBody(String firstName, Set<UUID> ids, long version) {
        return mapper.writeValueAsString(Map.of("firstName", firstName, "lastName", "Chen", "departmentIds", ids, "version", version));
    }

    private String activeBody(boolean active, long version) {
        return mapper.writeValueAsString(Map.of("active", active, "version", version));
    }
}
