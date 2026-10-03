# ClinicFlow interview walkthrough

Use fictional data and the [local run instructions](../README.md#run-locally).
The packaged PostgreSQL option is in [deployment](deployment.md); the H2 `demo`
profile is sufficient for the page demonstration. Concurrency and database-constraint
claims below are backed by PostgreSQL tests, not inferred from the browser demo.

## Opening explanation

> ClinicFlow is a Java 21 and Spring Boot application for inpatient workflows.
> I used healthcare workflow knowledge and relationship-modelling concepts to
> build registration, admission, placement, transfers, discharge corrections and
> physician responsibility. The main engineering problem is keeping current state
> and historical records consistent when users act on stale information or when
> operations compete. It uses REST/JSON APIs, JPA, PostgreSQL and a browser workbench.

Describe this repository as your implementation. Business reference material
informed the requirements; it is not the original employer's software, schema or
production deployment. Do not claim production users, measured business savings,
complete HIS coverage or capabilities that only appear in the reference material.

## Five-minute demonstration

1. **Register and admit.** Sign in as an operator, register a fictional patient,
   and admit them. Show the distinction between a patient and a hospital encounter.
   The waiting encounter appears on the inpatient worklist before department entry.
2. **Place the patient.** Select a department, ward and free bed. Explain that
   clinical department and physical ward are different concepts; care without a
   bed is also valid. The current location is an open history row.
3. **Assign responsibility.** Prepare an active physician affiliated with that
   department through the administrator directory. As operator, assign them and
   hand over to another eligible physician. Show retained responsibility history.
4. **Transfer and discharge.** Transfer to another department and show the old
   placement and physician period closed together. Discharge and inspect the
   timeline. The patient remains registered but leaves the active worklist.
5. **Correct and inspect.** Cancel the latest discharge where history permits it.
   Show the cancellation audit and restored location, then explain why physician
   responsibility requires a new explicit selection. Sign in as a viewer to show
   the same records with clinical writes unavailable.

The [automated demo](../scripts/demo-workflow.ps1) exercises the inpatient APIs;
[browser regressions](../e2e/README.md) reproduce page workflows and fault recovery.

## Code route and discussion points

| Question | Read | Explain |
| --- | --- | --- |
| Where does an operation begin? | [EncounterController](../src/main/java/com/jiangyudai/clinicflow/encounter/controller/EncounterController.java) | Validated request DTO, authenticated operator and one service call; no repository or managed entity in the controller. |
| What belongs in a transaction? | [EncounterService](../src/main/java/com/jiangyudai/clinicflow/encounter/service/EncounterService.java), `transferEncounter` | Lock and check the encounter, resolve/lock the destination, close responsibility when the department changes, close and flush the old location, then insert its replacement. All changes commit or roll back together. |
| Why does a flush appear before an insert? | [PostgresInpatientIntegrityIT](../src/test/java/com/jiangyudai/clinicflow/persistence/PostgresInpatientIntegrityIT.java) | The old open row must release PostgreSQL's partial unique key before the replacement insert. Flush is not commit; a later failure still restores the old state. |
| How are stale edits different from concurrent writes? | [EncounterPhysicianService](../src/main/java/com/jiangyudai/clinicflow/encounter/service/EncounterPhysicianService.java), [Physician](../src/main/java/com/jiangyudai/clinicflow/physician/entity/Physician.java) | Row locks serialize writes; expected IDs protect the user's last-seen location/responsibility. Directory versions reject stale profile and affiliation updates. |
| Why both many-to-many and an association entity? | `Physician`, [EncounterPhysicianAssignment](../src/main/java/com/jiangyudai/clinicflow/encounter/entity/EncounterPhysicianAssignment.java) | Current department membership has no relationship attributes. Responsibility needs effective times, closure reasons and operator audit, so it has its own identity. |
| Where are response objects assembled? | [EncounterQueryService](../src/main/java/com/jiangyudai/clinicflow/encounter/service/EncounterQueryService.java) | Inside the transaction, before lazy relationships become detached. Histories are queried explicitly rather than exposed as parent entity collections. |
| How was query performance checked? | [Query measurements](query-performance.md), `PostgresQueryPlansIT` | Explain the recorded dataset, query counts and plans. State their scale and limits; do not turn a local measurement into a production throughput claim. |
| How are UI recovery decisions kept stable? | [ApiErrorCode](../src/main/java/com/jiangyudai/clinicflow/web/error/ApiErrorCode.java), [patient-admission.js](../src/main/resources/static/patient-admission.js) | The error code is a contract; display wording can change. A lost response does not prove the write failed, so refresh history before deliberately retrying. |
| How is the structure kept consistent? | [ArchitectureTest](../src/test/java/com/jiangyudai/clinicflow/architecture/ArchitectureTest.java), [BusinessClockIntegrationTest](../src/test/java/com/jiangyudai/clinicflow/common/time/BusinessClockIntegrationTest.java) | Dependency rules run in the normal test suite. A fixed clock verifies request and service validation at the same instant, including a different UTC offset. |

## Tradeoffs to defend

- A feature-organised monolith keeps cross-record transactions understandable.
  It has shared JPA references and database queries across features; it is not a
  set of strictly isolated services.
- The workbench uses native JavaScript modules. The patient entry point composes
  list, registration, record and admission components with explicit callbacks.
  This is enough for the implemented interface without a framework migration.
- H2 supports fast local tests and demos. PostgreSQL tests are required for row
  locks, migrations, partial indexes and execution plans. Browser tests verify
  user interaction; container CI verifies persistence across recreation.
- Validation appears at different boundaries for different reasons: DTO constraints
  reject bad input, entities protect local transitions, services check related
  records under locks, and database constraints guard persisted invariants.
- Cancelling a discharge is a correction with history, not deletion. It cannot
  silently overwrite subsequent care or another patient's bed usage. It restores
  effective location history from discharge time while recording when correction
  was performed; it does not reinstate a physician without a new selection.

## Scope and next work

The implemented scope is inpatient administration and responsibility. Medication
orders, billing, outpatient scheduling, reference-data administration and password
reset are not implemented. A public deployment is not provisioned. Start interview
practice with the existing workflow and its tests before adding another business
module. Future features should have a concrete use case, transaction boundary and
demonstrable result; adding more mappings alone is not a reason to expand scope.
