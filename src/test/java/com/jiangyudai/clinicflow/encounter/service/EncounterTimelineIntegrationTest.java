package com.jiangyudai.clinicflow.encounter.service;

import com.jayway.jsonpath.JsonPath;
import com.jiangyudai.clinicflow.encounter.dto.EncounterTimelineResponse;
import com.jiangyudai.clinicflow.encounter.entity.Encounter;
import com.jiangyudai.clinicflow.encounter.entity.EncounterLocation;
import com.jiangyudai.clinicflow.encounter.entity.EncounterStatus;
import com.jiangyudai.clinicflow.location.entity.Bed;
import com.jiangyudai.clinicflow.location.entity.Department;
import com.jiangyudai.clinicflow.location.entity.Ward;
import com.jiangyudai.clinicflow.patient.entity.Patient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:encounter-timeline-it;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.open-in-view=false"
})
@AutoConfigureMockMvc
class EncounterTimelineIntegrationTest {

    private static final OffsetDateTime ADMITTED_AT = OffsetDateTime.parse("2025-09-01T08:00:00-04:00");

    @Autowired
    private EncounterService encounterService;
    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        transactions = new TransactionTemplate(transactionManager);
    }

    @Test
    void returnsAdmissionWithEmptyLocationAndDischargeHistory() throws Exception {
        TimelineData data = createData();

        String response = mockMvc.perform(get("/api/v1/encounters/{id}/timeline", data.encounterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.id").value(data.encounterId().toString()))
                .andExpect(jsonPath("$.encounter.patientId").value(data.patientId().toString()))
                .andExpect(jsonPath("$.encounter.status").value("ADMITTED"))
                .andExpect(jsonPath("$.encounter.dischargedAt").value(nullValue()))
                .andExpect(jsonPath("$.locations").isEmpty())
                .andExpect(jsonPath("$.discharges").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertInstant(response, "$.encounter.admittedAt", ADMITTED_AT);
    }

    @Test
    void returnsCancelledAdmissionAuditWithoutInventingLocationHistory() throws Exception {
        TimelineData data = createData();
        encounterService.cancelAdmission(data.encounterId(), ADMITTED_AT.plusHours(1), "admission-clerk");

        String response = mockMvc.perform(get("/api/v1/encounters/{id}/timeline", data.encounterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.status").value("ADMISSION_CANCELLED"))
                .andExpect(jsonPath("$.encounter.admissionCancelledBy").value("admission-clerk"))
                .andExpect(jsonPath("$.locations").isEmpty())
                .andExpect(jsonPath("$.discharges").isEmpty())
                .andReturn().getResponse().getContentAsString();

        assertInstant(response, "$.encounter.admissionCancelledAt", ADMITTED_AT.plusHours(1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void returnsTransferAndCancellationLinksWithContinuousEffectiveHistory(boolean withBed) throws Exception {
        TimelineData data = createData();
        EncounterLocation first = enter(data, withBed ? data.bedId() : null);
        EncounterLocation transferred = encounterService.transferEncounter(
                data.encounterId(), data.departmentId(), data.wardId(), data.otherBedId(), ADMITTED_AT.plusHours(2)
        );
        encounterService.dischargeEncounter(data.encounterId(), ADMITTED_AT.plusHours(3));
        EncounterTimelineResponse discharged = encounterService.getTimeline(data.encounterId());
        assertThat(discharged.encounter().status()).isEqualTo(EncounterStatus.DISCHARGED);
        assertThat(discharged.locations()).allSatisfy(location -> assertThat(location.endedAt()).isNotNull());

        encounterService.cancelDischarge(data.encounterId(), ADMITTED_AT.plusHours(4), "discharge-clerk");
        String response = mockMvc.perform(get("/api/v1/encounters/{id}/timeline", data.encounterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.encounter.status").value("IN_DEPARTMENT"))
                .andExpect(jsonPath("$.encounter.dischargedAt").value(nullValue()))
                .andExpect(jsonPath("$.locations.length()").value(3))
                .andExpect(jsonPath("$.locations[0].id").value(first.getId().toString()))
                .andExpect(jsonPath("$.locations[0].bedId").value(withBed ? equalTo(data.bedId().toString()) : nullValue()))
                .andExpect(jsonPath("$.locations[1].id").value(transferred.getId().toString()))
                .andExpect(jsonPath("$.locations[2].endedAt").value(nullValue()))
                .andExpect(jsonPath("$.locations[2].bedId").value(data.otherBedId().toString()))
                .andExpect(jsonPath("$.discharges.length()").value(1))
                .andExpect(jsonPath("$.discharges[0].locationId").value(transferred.getId().toString()))
                .andExpect(jsonPath("$.discharges[0].cancelledBy").value("discharge-clerk"))
                .andReturn().getResponse().getContentAsString();

        String restoredId = JsonPath.read(response, "$.locations[2].id");
        assertThat((String) JsonPath.read(response, "$.discharges[0].restoredLocationId")).isEqualTo(restoredId);
        assertInstant(response, "$.locations[1].endedAt", ADMITTED_AT.plusHours(3));
        assertInstant(response, "$.locations[2].startedAt", ADMITTED_AT.plusHours(3));
        assertInstant(response, "$.discharges[0].dischargedAt", ADMITTED_AT.plusHours(3));
        assertInstant(response, "$.discharges[0].cancelledAt", ADMITTED_AT.plusHours(4));
        assertThat(readJson(data.encounterId())).isEqualTo(response);

        encounterService.dischargeEncounter(data.encounterId(), ADMITTED_AT.plusHours(5));
        EncounterTimelineResponse finalTimeline = encounterService.getTimeline(data.encounterId());
        assertThat(finalTimeline.encounter().dischargedAt()).isEqualTo(ADMITTED_AT.plusHours(5));
        assertThat(finalTimeline.locations()).hasSize(3);
        assertThat(finalTimeline.locations()).allSatisfy(location -> assertThat(location.endedAt()).isNotNull());
        assertThat(finalTimeline.discharges()).hasSize(2);
        assertThat(finalTimeline.discharges().getFirst().cancelledBy()).isEqualTo("discharge-clerk");
        assertThat(finalTimeline.discharges().getLast().locationId().toString()).isEqualTo(restoredId);
        assertThat(finalTimeline.discharges().getLast().cancelledAt()).isNull();
    }

    @Test
    void preservesSameInstantRecordsAndTheirExplicitLinksInStableOrder() throws Exception {
        TimelineData data = createData();
        OffsetDateTime time = ADMITTED_AT.plusHours(1);
        EncounterLocation first = enter(data, data.bedId());
        EncounterLocation transferred = encounterService.transferEncounter(
                data.encounterId(), data.departmentId(), data.wardId(), data.otherBedId(), time
        );
        encounterService.dischargeEncounter(data.encounterId(), time);
        encounterService.cancelDischarge(data.encounterId(), time.plusHours(1), "first-clerk");
        UUID firstRestored = encounterService.getDischarges(data.encounterId()).getFirst().getRestoredLocation().getId();
        encounterService.dischargeEncounter(data.encounterId(), time);
        encounterService.cancelDischarge(data.encounterId(), time.plusHours(2), "second-clerk");

        EncounterTimelineResponse timeline = encounterService.getTimeline(data.encounterId());
        assertThat(timeline.locations()).hasSize(4);
        assertThat(timeline.locations()).allSatisfy(location -> assertThat(location.startedAt()).isEqualTo(time));
        assertThat(timeline.locations().stream().map(location -> location.id().toString()).toList()).isSorted();
        assertThat(timeline.discharges().stream().map(discharge -> discharge.id().toString()).toList()).isSorted();
        assertThat(timeline.locations().stream().filter(location -> location.endedAt() == null).toList()).hasSize(1);
        assertThat(timeline.discharges()).hasSize(2).anySatisfy(discharge -> {
            assertThat(discharge.locationId()).isEqualTo(transferred.getId());
            assertThat(discharge.restoredLocationId()).isEqualTo(firstRestored);
        }).anySatisfy(discharge -> {
            assertThat(discharge.locationId()).isEqualTo(firstRestored);
            assertThat(discharge.cancelledBy()).isEqualTo("second-clerk");
        });
        assertThat(timeline.locations()).anySatisfy(location -> {
            assertThat(location.id()).isEqualTo(first.getId());
            assertThat(location.endedAt()).isEqualTo(location.startedAt());
        });
        assertThat(readJson(data.encounterId())).isEqualTo(readJson(data.encounterId()));
    }

    @Test
    void doesNotIncludeAnotherEncounterForTheSamePatient() {
        TimelineData first = createData();
        EncounterLocation firstLocation = enter(first, null);
        encounterService.dischargeEncounter(first.encounterId(), ADMITTED_AT.plusHours(2));
        Encounter later = encounterService.admitPatient(first.patientId(), "ENC-" + UUID.randomUUID(), ADMITTED_AT.plusDays(1));
        EncounterLocation laterLocation = encounterService.admitToDepartment(
                later.getId(), first.departmentId(), first.wardId(), first.bedId(), ADMITTED_AT.plusDays(1)
        );

        EncounterTimelineResponse firstTimeline = encounterService.getTimeline(first.encounterId());
        EncounterTimelineResponse laterTimeline = encounterService.getTimeline(later.getId());

        assertThat(firstTimeline.locations()).singleElement().satisfies(location ->
                assertThat(location.id()).isEqualTo(firstLocation.getId()));
        assertThat(firstTimeline.discharges()).singleElement().satisfies(discharge ->
                assertThat(discharge.encounterId()).isEqualTo(first.encounterId()));
        assertThat(laterTimeline.locations()).singleElement().satisfies(location ->
                assertThat(location.id()).isEqualTo(laterLocation.getId()));
        assertThat(laterTimeline.discharges()).isEmpty();
    }

    @Test
    void retainsHistoricalReferencesAfterTheyAreDeactivated() throws Exception {
        TimelineData data = createData();
        enter(data, data.bedId());
        encounterService.dischargeEncounter(data.encounterId(), ADMITTED_AT.plusHours(2));
        transactions.executeWithoutResult(status -> {
            entityManager.createNativeQuery("update departments set active = false where id = ?1")
                    .setParameter(1, data.departmentId()).executeUpdate();
            entityManager.createNativeQuery("update wards set active = false where id = ?1")
                    .setParameter(1, data.wardId()).executeUpdate();
            entityManager.createNativeQuery("update beds set active = false where id = ?1")
                    .setParameter(1, data.bedId()).executeUpdate();
        });

        mockMvc.perform(get("/api/v1/encounters/{id}/timeline", data.encounterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locations[0].departmentId").value(data.departmentId().toString()))
                .andExpect(jsonPath("$.locations[0].wardId").value(data.wardId().toString()))
                .andExpect(jsonPath("$.locations[0].bedId").value(data.bedId().toString()));
    }

    @Test
    void returnsNotFoundForAnUnknownEncounter() throws Exception {
        mockMvc.perform(get("/api/v1/encounters/{id}/timeline", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Encounter not found"));
    }

    @Test
    void rejectsAMalformedEncounterId() throws Exception {
        mockMvc.perform(get("/api/v1/encounters/not-a-uuid/timeline")).andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitsForDischargeThenReadsOnlyCommittedState(boolean rollback) throws Exception {
        TimelineData data = createData();
        enter(data, data.bedId());
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<EncounterTimelineResponse> read = transactions.execute(status -> {
                encounterService.dischargeEncounter(data.encounterId(), ADMITTED_AT.plusHours(2));
                entityManager.flush();
                int sessionId = currentSessionId();
                Future<EncounterTimelineResponse> pending = executor.submit(() -> encounterService.getTimeline(data.encounterId()));
                awaitBlockedWorkflow(sessionId, pending);
                if (rollback) {
                    status.setRollbackOnly();
                }
                return pending;
            });

            assertThat(read).isNotNull();
            EncounterTimelineResponse timeline = read.get(10, TimeUnit.SECONDS);
            assertThat(timeline.encounter().status()).isEqualTo(rollback ? EncounterStatus.IN_DEPARTMENT : EncounterStatus.DISCHARGED);
            assertThat(timeline.discharges()).hasSize(rollback ? 0 : 1);
            assertThat(timeline.locations()).singleElement().satisfies(location ->
                    assertThat(location.endedAt()).isEqualTo(rollback ? null : ADMITTED_AT.plusHours(2)));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void holdsTheEncounterLockUntilTheTimelineHasBeenMaterialized() throws Exception {
        TimelineData data = createData();
        enter(data, data.bedId());
        var executor = Executors.newSingleThreadExecutor();
        try {
            ConcurrentRead read = transactions.execute(status -> {
                EncounterTimelineResponse timeline = encounterService.getTimeline(data.encounterId());
                int sessionId = currentSessionId();
                Future<?> writer = executor.submit(() -> encounterService.dischargeEncounter(data.encounterId(), ADMITTED_AT.plusHours(2)));
                awaitBlockedWorkflow(sessionId, writer);
                return new ConcurrentRead(timeline, writer);
            });

            assertThat(read).isNotNull();
            read.writer().get(10, TimeUnit.SECONDS);
            assertThat(read.timeline().encounter().status()).isEqualTo(EncounterStatus.IN_DEPARTMENT);
            assertThat(read.timeline().locations().getFirst().endedAt()).isNull();
            assertThat(read.timeline().discharges()).isEmpty();
            assertThat(encounterService.getTimeline(data.encounterId()).encounter().status()).isEqualTo(EncounterStatus.DISCHARGED);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private int currentSessionId() {
        return ((Number) entityManager.createNativeQuery("select session_id()").getSingleResult()).intValue();
    }

    private void awaitBlockedWorkflow(int sessionId, Future<?> attempt) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Number waiting = (Number) entityManager.createNativeQuery(
                    "select count(*) from information_schema.sessions where blocker_id = ?1"
            ).setParameter(1, sessionId).getSingleResult();
            if (waiting.intValue() > 0) {
                return;
            }
            assertThat(attempt.isDone()).as("workflow must wait for the encounter lock").isFalse();
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while checking the encounter lock", exception);
            }
        }
        throw new AssertionError("Concurrent workflow did not wait for the encounter lock");
    }

    private String readJson(UUID encounterId) throws Exception {
        return mockMvc.perform(get("/api/v1/encounters/{id}/timeline", encounterId))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void assertInstant(String response, String path, OffsetDateTime expected) {
        assertThat(OffsetDateTime.parse(JsonPath.read(response, path)).toInstant()).isEqualTo(expected.toInstant());
    }

    private EncounterLocation enter(TimelineData data, UUID bedId) {
        return encounterService.admitToDepartment(data.encounterId(), data.departmentId(), data.wardId(), bedId, ADMITTED_AT.plusHours(1));
    }

    private TimelineData createData() {
        return transactions.execute(status -> {
            String suffix = UUID.randomUUID().toString().substring(0, 8);
            Patient patient = new Patient("MRN-" + UUID.randomUUID(), "Test", "Patient", LocalDate.of(1990, 5, 14));
            Department department = new Department("DEPT-" + suffix, "Test Department");
            Ward ward = new Ward("WARD-" + suffix, "Test Ward");
            entityManager.persist(patient);
            entityManager.persist(department);
            entityManager.persist(ward);
            Bed bed = new Bed("01", ward);
            Bed otherBed = new Bed("02", ward);
            entityManager.persist(bed);
            entityManager.persist(otherBed);
            entityManager.flush();
            Encounter encounter = encounterService.admitPatient(patient.getId(), "ENC-" + suffix, ADMITTED_AT);
            return new TimelineData(patient.getId(), encounter.getId(), department.getId(), ward.getId(), bed.getId(), otherBed.getId());
        });
    }

    private record TimelineData(UUID patientId, UUID encounterId, UUID departmentId,
                                UUID wardId, UUID bedId, UUID otherBedId) {
    }

    private record ConcurrentRead(EncounterTimelineResponse timeline, Future<?> writer) {
    }
}
