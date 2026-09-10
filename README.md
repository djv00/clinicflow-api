# clinicflow-api
A healthcare encounter workflow and system integration API built with Java and Spring Boot.

The current workflow covers patient registration, inpatient admission, department
admission, transfers, discharge, admission cancellation, and discharge cancellation. Integration delivery
is not implemented yet. A timeline query exposes each encounter's effective
location history and discharge audit.
Department, ward, and bed queries resolve the location IDs used by those workflows.

## Run locally

Requires JDK 21. On Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
```

The API starts on port 8080. By default, it uses an in-memory H2 database that is
cleared on shutdown. Default startup does not load reference data, so location
list queries return empty arrays. Reference-data creation and maintenance
endpoints are not available yet.

### PostgreSQL

The `postgres` profile stores data in PostgreSQL. Flyway applies versioned SQL
migrations on startup; Hibernate validates the schema without creating or dropping
tables. The default H2 configuration and its tests continue to use Hibernate.

With Docker Desktop running, start the local database in the same terminal used
to run the application:

```powershell
$env:DB_PASSWORD = 'choose-a-local-password'
docker compose up -d --wait postgres
.\mvnw.cmd '-Dspring-boot.run.profiles=postgres' spring-boot:run
```

The Compose service binds PostgreSQL to localhost port 5432, creates the
`clinicflow` database and user, and stores data in a named volume. Stop it with
`docker compose stop postgres`; restarting the application or database preserves
the data. The password initializes a new volume; changing the environment variable
does not change the password in an existing database.

An existing PostgreSQL server can be used without Docker. Create an empty database
owned by an application user, then set these variables before starting the profile:

```powershell
$env:DB_URL = 'jdbc:postgresql://localhost:5432/clinicflow'
$env:DB_USERNAME = 'clinicflow'
$env:DB_PASSWORD = 'your-database-password'
.\mvnw.cmd '-Dspring-boot.run.profiles=postgres' spring-boot:run
```

`DB_URL` and `DB_USERNAME` default to the values shown above; `DB_PASSWORD` is
required. Keep actual credentials outside the repository. Use `postgres` and
`demo` separately: the demo dictionary is only loaded into the disposable H2
database. PostgreSQL starts without patient or reference data.

The first migration matches the existing patient-flow entities, including foreign
keys, unique constraints and history indexes. Add a new versioned migration for
later schema changes instead of editing a migration already applied to a database.

### Demo workflow

To load fictional reference data, stop the application and start it with the
optional `demo` profile in the same JDK 21 terminal:

```powershell
.\mvnw.cmd '-Dspring-boot.run.profiles=demo' spring-boot:run
```

For a packaged application:

```powershell
.\mvnw.cmd clean verify
java -jar target/clinicflow-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=demo
```

The profile loads two departments, two wards, and three beds after Hibernate
creates the H2 tables. Both wards have an active bed numbered `01`; the first ward
also has an inactive bed `02`. The IDs are fixed in `src/main/resources/demo/data.sql`.
No patients or encounters are created at startup. This is local sample data, not
an import of the supplied HIS messages or a database migration.

With the demo application running, open another PowerShell terminal in the
repository root:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\demo-workflow.ps1
# If the application uses another port:
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\demo-workflow.ps1 -BaseUrl http://localhost:8081
```

`RemoteSigned` applies only to this process and does not change the saved
PowerShell execution policy.

The script queries reference IDs, registers a fictional patient, cancels an
admission before department entry, then admits the patient again. It enters the
first ward, transfers to the second, discharges, cancels discharge, and discharges
again. It checks bed occupancy and the discharge audit along the way, then prints
the patient/encounter IDs and the final timeline JSON and URL.

A completed run leaves three closed location intervals, two discharge records
(one cancelled), and both active beds free. Each run generates new record numbers
and uses current UTC operation times, so sequential runs can share the same demo
database. Use one run at a time with both demo beds free. If a request fails, the
script stops and earlier successful operations remain available for inspection.
Restarting the application clears all H2 data and reloads only the demo dictionary.

## Departments, wards, and beds

| Endpoint | Optional filters | Response fields |
| --- | --- | --- |
| `GET /api/v1/departments` | `active` | `id`, `departmentCode`, `departmentName`, `active` |
| `GET /api/v1/wards` | `active` | `id`, `wardCode`, `wardName`, `active` |
| `GET /api/v1/beds` | `wardId`, `active`, `occupied` | `id`, `bedNumber`, `wardId`, `active`, `occupied` |

Each resource also supports `GET /api/v1/{resource}/{id}` to resolve one reference,
including inactive records found in encounter history. Successful requests return
`200`; an unknown detail ID returns `404`; malformed UUID or boolean values return `400`.

```http
GET /api/v1/departments?active=true
GET /api/v1/wards?active=true
GET /api/v1/beds?wardId=20000000-0000-0000-0000-000000000001&active=true&occupied=false
```

Use the ward ID returned by the ward query; the UUID above is an example, not a
record created by the default application startup.

- Omitted filters include all values. `active=false` selects inactive records;
  `occupied=false` selects beds without an open location. Filters combine with AND.
- An unmatched filter, including a well-formed but unknown `wardId`, returns `[]`.
  Lists currently return all matches without pagination. Department and ward lists
  sort by their code; beds sort by ward code, bed number, then ID. Bed numbers are
  unique within a ward, so different wards may contain the same number.
- `occupied` means an `EncounterLocation` references the bed with `endedAt = null`.
  Closed history does not count as current occupancy. Dictionary fields and
  occupancy are selected together in one query, without reserving beds.
- `active` on a bed describes the bed itself, independently of its ward's status
  or occupancy. A bed can be inactive and occupied. An active, unoccupied bed in
  an inactive ward still cannot be assigned. Query the ward to check its status.
- A query result is advisory: admission and transfer still validate active
  references, current occupancy, historical conflicts, and workflow times while
  holding their existing locks. An unoccupied result does not guarantee that a
  later or backdated assignment will succeed.

The supplied dictionary samples include `BED_NO`, `WARD_CODE`, and `DEFUNCT_IND`;
department samples distinguish specialty and subspecialty codes. This API retains
ClinicFlow's existing `bedNumber`, ward, department, and `active` fields. External
code mapping and message ingestion remain separate work. The available material
does not establish a one-to-one department/ward relationship, so this query adds
no such constraint or inferred department filter on wards or beds.

## Encounter timeline

```http
GET /api/v1/encounters/{id}/timeline
```

Returns `200 OK` with three fields, reusing the existing response formats:

| Field | Contents |
| --- | --- |
| `encounter` | Encounter ID and number, patient ID, current status, admission/discharge times, and admission cancellation details. |
| `locations` | Effective department, ward, and optional bed intervals, ordered by `startedAt`, then ID. |
| `discharges` | Discharge records with cancellation time/operator and original/restored location IDs, ordered by `dischargedAt`, then ID. |

- An admitted patient who has not entered a department has empty `locations` and
  `discharges` arrays. A cancelled admission retains its cancellation details in
  `encounter`; it does not create a location or discharge record.
- `endedAt: null` identifies the current location; `bedId: null` means no bed was
  assigned. Do not assume the last array entry is current when timestamps tie.
- After discharge cancellation, `locationId` and `restoredLocationId` link the
  original interval to its continuation. The continuation starts at the original
  discharge time; `cancelledAt` is the operation time. Cancelled discharge records
  remain visible and must not be treated as an effective departure.
- Same-instant records, including zero-duration locations, are retained. ID is
  only a stable display tie-breaker, not proof of the order in which operations
  occurred. This query does not invent missing event timestamps or operators.
- Historical references remain visible even if a department, ward, or bed is
  subsequently deactivated. Results are scoped to one encounter, not every
  encounter belonging to the patient.
- The query briefly locks the encounter against workflow changes while loading
  and materializing the response. It may wait for a concurrent workflow, so all
  three fields reflect the same committed workflow state. It changes no records.

An unknown encounter returns `404`; a malformed UUID returns `400`. The query
returns the full history of this encounter and currently has no pagination.

## Admission and bed history

New admissions and location assignments open intervals without an end time.
The API supports past timestamps subject to these rules:

- A new admission cannot start before any existing `DISCHARGED` encounter for
  the same patient has ended. Cancelled admissions do not block a replacement.
  The existing restriction to one active encounter per patient still applies.
- Department admission, transfer, and discharge cancellation check both current
  bed occupancy and closed bed history. A new assignment cannot overlap a
  non-empty closed interval for that bed, even when the bed is free now.
- Bed intervals are `[startedAt, endedAt)`: the next assignment may start exactly
  when the previous one ends. Zero-duration closed locations do not occupy time.
  Comparisons use instants, including when requests use different UTC offsets.
- History conflicts return `409` with title `Encounter history conflict` and a
  patient- or bed-specific detail. Failed requests leave workflow records unchanged.
  Checks run within the existing patient or bed write lock.

These are ClinicFlow consistency rules, not restrictions established by the
source message samples. Importing complete historical encounters out of order
and correcting existing history are outside these creation endpoints' scope.

## Discharge

```http
POST /api/v1/encounters/{id}/discharges
Content-Type: application/json

{
  "dischargedAt": "2025-09-03T14:00:00-04:00"
}
```

Returns `200 OK` with the updated encounter, including `status: "DISCHARGED"`
and `dischargedAt`.

- The encounter must be `IN_DEPARTMENT` and have a current location.
- The timestamp must include an offset, cannot be in the future, and cannot
  precede hospital admission or the current location's start time. Equal times
  are allowed.
- Discharge closes the current location at the same timestamp, preserving
  earlier location history. An assigned bed becomes available when the transaction commits.
- Each discharge also creates an `encounter_discharges` record referencing the
  exact closed location. Its discharge time is retained if care resumes later.
- The encounter write lock serializes discharge with transfers and repeated
  discharge attempts. An error rolls back the encounter, location, and discharge record together.

An unknown encounter returns `404`; invalid time or request data returns `400`;
an invalid state, repeated discharge, or missing current location returns `409`.
This endpoint does not record discharge classification or billing totals.

## Cancel admission

```http
POST /api/v1/encounters/{id}/admission-cancellations
Content-Type: application/json

{
  "cancelledAt": "2025-09-01T14:30:00-04:00",
  "cancelledBy": "demo-clerk"
}
```

Returns `200 OK` with `status: "ADMISSION_CANCELLED"`, `admissionCancelledAt`,
and `admissionCancelledBy`. These two fields are null before cancellation.

- This workflow supports cancellation before department care starts: the
  encounter must be `ADMITTED` with no location history, including closed records.
- The cancellation time is required, must include an offset, cannot be in the
  future, and cannot precede admission. Equal times are allowed.
- The operator is required and limited to 100 characters. It is a caller-supplied
  identifier; authentication and user lookup are not implemented yet.
- Cancellation retains the encounter and its original number. The patient can
  have a new admission with a new encounter number afterwards.
- Repeated cancellation returns `409` and preserves the original cancellation
  details. Department admission and cancellation use the same encounter write lock.

Unknown encounters return `404`; invalid request data returns `400`; an invalid
state or existing location history returns `409`. Cancellation of an encounter
that has already entered a department, and reversal of discharge, are outside
this endpoint's scope.

The source cancellation messages provide operation time and operator fields
(`operate_datetime` and `operate_by`). The REST API uses `cancelledAt` and
`cancelledBy` for this action. The restriction to admissions without department
history is a ClinicFlow workflow rule; the message samples do not specify it.

## Cancel discharge

```http
POST /api/v1/encounters/{id}/discharge-cancellations
Content-Type: application/json

{
  "cancelledAt": "2025-09-03T15:00:00-04:00",
  "cancelledBy": "demo-clerk"
}
```

Returns `200 OK` with `status: "IN_DEPARTMENT"` and `dischargedAt: null`.

- The encounter must be `DISCHARGED`, have a matching uncancelled discharge
  record, and have no current location. The patient cannot have another active
  encounter (`ADMITTED` or `IN_DEPARTMENT`).
- Another non-cancelled encounter starting at or after this discharge, or ending
  after it, also prevents cancellation, even if that encounter has finished.
  Same-instant admissions are treated as conflicts because their order is ambiguous.
  An `ADMISSION_CANCELLED` record does not block correction of the earlier discharge.
- The cancellation time must include an offset, be at or after discharge, and
  not be in the future. The operator is required and limited to 100 characters;
  as with admission cancellation, it is supplied by the caller.
- Effective care continues at the department, ward, and optional bed referenced
  by that discharge. These references must still be active. The bed must be free
  now and have no conflicting occupancy since the original discharge, including
  assignments that ended before cancellation. This endpoint does not accept a replacement bed.
- The original location remains closed at `dischargedAt`. A continuation location
  starts at that same instant, leaving no gap or overlap in effective occupancy.
  These adjacent records describe continuous care, not a physical transfer.
  `cancelledAt` records when the cancellation was operated; it does not set the
  start of effective care. For example, a 10:00 mistaken discharge cancelled at
  10:15 produces intervals ending and starting at 10:00, with 10:15 retained in audit.
- The discharge record retains `discharged_at` and `location_id`, and records
  `cancelled_at`, `cancelled_by`, and `restored_location_id`. A later discharge
  creates a separate record, so repeated discharge/cancellation cycles remain traceable.
- Admission and discharge cancellation take a patient write lock before checking
  for conflicting encounters. Cancellation then locks the encounter and, when needed,
  the bed (`Patient -> Encounter -> Bed`). Existing location workflows retain
  their `Encounter -> Bed` order. This prevents concurrent readmission or bed
  assignment from passing the same availability check.
- All cancellation changes commit together. A repeated cancellation returns
  `409` without changing the first cancellation's details.

Unknown encounters return `404`. Invalid request data, time, or inactive location
references return `400`. Invalid encounter state, another active encounter,
conflicting encounter or bed history, an occupied bed, or inconsistent
discharge/location records return `409`. Advancing `cancelledAt` cannot bypass
a historical conflict. A genuine readmission requires a new encounter.

### Discharge history

```http
GET /api/v1/encounters/{id}/discharges
```

Returns a list ordered by discharge time and ID. Each item contains `id`,
`encounterId`, `locationId`, `dischargedAt`, `cancelledAt`, `cancelledBy`, and
`restoredLocationId`. Cancellation fields are null until cancelled. An existing
encounter with no discharges returns `[]`; an unknown encounter returns `404`.

### Source mapping and scope

The supplied discharge-cancellation samples contain these fields:

| Message field | ClinicFlow use |
| --- | --- |
| `patient_no`, `visit_no` | External patient/encounter identifiers; a future integration adapter must resolve local IDs. |
| `dept_code`, `ward_code`, `bed_no` | Location references; the REST workflow restores the saved discharge location. |
| `operate_datetime` | `cancelledAt` in the request and `cancelled_at` in the discharge record. |
| `operate_by` | `cancelledBy` in the request and `cancelled_by` in the discharge record. |

The supplied `VISIT` and `VISITWARDHISTORY` diagrams inform the separation of
encounter state and location history. The samples establish field names, but do
not specify reversal rules. Restoring the original location, checking historical
availability, and continuing occupancy from discharge are ClinicFlow design choices.
The original system's reversal behavior has not been verified. XML ingestion,
billing reversal, and alternative-bed selection are not implemented.

The current H2 database is recreated on startup. Existing persistent discharge
data would require a migration before enabling this workflow; missing discharge
records are rejected rather than reconstructed from timestamps. Data created by
the earlier cancellation implementation, where continuation started at operation
time, would need conflict review before correcting those intervals. This version
does not silently rewrite existing records.

## Tests

```powershell
.\mvnw.cmd test
.\mvnw.cmd '-Dtest=EncounterDischarge*Test' test
.\mvnw.cmd '-Dtest=AdmissionCancellation*Test' test
.\mvnw.cmd '-Dtest=DischargeCancellation*Test' test
.\mvnw.cmd '-Dtest=EncounterHistoryIntegrationTest' test
.\mvnw.cmd '-Dtest=EncounterTimelineIntegrationTest' test
.\mvnw.cmd '-Dtest=LocationQueryIntegrationTest' test
```

Discharge tests cover the API, state and time rules, location history, bed reuse,
readmission, rollback after a database flush, and concurrent discharge/transfer
requests. Database and locking tests currently run against H2.

Cancellation tests cover request validation, operator/time persistence, retained
encounter numbers, readmission, rejection of open or closed location history,
rollback after flush, and concurrent cancellation/department admission.

Discharge cancellation tests cover retained discharge records, restoration with
or without a bed, multiple cancellation cycles, equal transfer/discharge times,
continuous effective history, later completed encounters, intervening bed use,
inactive references, rollback after flush, and concurrent cancellation,
readmission, and bed assignment, including workflows that finish before releasing
their locks. These locking checks use H2 at `READ_COMMITTED`;
they do not establish behavior on a different database or isolation level.

History tests cover backdated admissions and bed assignments, exact boundaries
across UTC offsets, cancelled admissions, zero-duration locations, failed transfers
and cancellations, and waiting workflows reading newly committed closed history.

Timeline tests cover admission/cancellation, transfers, repeated discharge and
cancellation, same-instant ordering and links, nullable beds, deactivated references,
encounter isolation, and concurrent reads with committed or rolled-back workflows.
DTOs are materialized within the transaction with Open EntityManager in View disabled.

Location query tests cover dictionary codes and names, inactive detail lookup,
combined filters, identical bed numbers in different wards, and occupancy changes
through admission, transfer, discharge, and cancellation. They also verify that
current occupancy does not override historical conflicts or inactive-ward rules.
