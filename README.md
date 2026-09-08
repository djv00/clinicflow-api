# clinicflow-api
A healthcare encounter workflow and system integration API built with Java and Spring Boot.

The current workflow covers patient registration, inpatient admission, department
admission, transfers, discharge, admission cancellation, and discharge cancellation. Integration delivery
is not implemented yet.

## Run locally

Requires JDK 21. On Windows PowerShell:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-21'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\mvnw.cmd spring-boot:run
```

The API starts on port 8080. It currently uses an in-memory H2 database that is
cleared on shutdown. Department, ward, and bed data are set up by the integration
tests; reference-data maintenance endpoints are not available yet.

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
- The cancellation time must include an offset, be at or after discharge, and
  not be in the future. The operator is required and limited to 100 characters;
  as with admission cancellation, it is supplied by the caller.
- Care resumes at the department, ward, and optional bed referenced by that
  discharge. These references must still be active, and an assigned bed must
  be free. This endpoint does not accept a replacement bed.
- The original location remains closed at discharge. A new current location
  starts at `cancelledAt`; the interval between these two times stays in history.
  Cancelling a discharge does not retrospectively extend the old bed assignment.
- The discharge record retains `discharged_at` and `location_id`, and records
  `cancelled_at`, `cancelled_by`, and `restored_location_id`. A later discharge
  creates a separate record, so repeated discharge/cancellation cycles remain traceable.
- Admission and discharge cancellation take a patient write lock before checking
  for active encounters. Cancellation then locks the encounter and, when needed,
  the bed (`Patient -> Encounter -> Bed`). Existing location workflows retain
  their `Encounter -> Bed` order. This prevents concurrent readmission or bed
  assignment from passing the same availability check.
- All cancellation changes commit together. A repeated cancellation returns
  `409` without changing the first cancellation's details.

Unknown encounters return `404`. Invalid request data, time, or inactive location
references return `400`. Invalid encounter state, another active encounter,
an occupied bed, or inconsistent discharge/location records return `409`.

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
not specify reversal rules. Restoring the original location, checking current
availability, and starting a new location interval are ClinicFlow design choices.
The original system's reversal behavior has not been verified. XML ingestion,
billing reversal, and alternative-bed selection are not implemented.

The current H2 database is recreated on startup. Existing persistent discharge
data would require a migration before enabling this workflow; missing discharge
records are rejected rather than reconstructed from timestamps.

Known limitations of this initial cancellation workflow: it does not yet reject
cancellation of an older encounter after a later encounter has been discharged.
It also starts the restored location at the operation time, so it cannot yet
represent uninterrupted occupancy following a mistakenly recorded discharge.
Both require a separate correction of the cancellation contract and history.

## Tests

```powershell
.\mvnw.cmd test
.\mvnw.cmd '-Dtest=EncounterDischarge*Test' test
.\mvnw.cmd '-Dtest=AdmissionCancellation*Test' test
.\mvnw.cmd '-Dtest=DischargeCancellation*Test' test
.\mvnw.cmd '-Dtest=EncounterHistoryIntegrationTest' test
```

Discharge tests cover the API, state and time rules, location history, bed reuse,
readmission, rollback after a database flush, and concurrent discharge/transfer
requests. Database and locking tests currently run against H2.

Cancellation tests cover request validation, operator/time persistence, retained
encounter numbers, readmission, rejection of open or closed location history,
rollback after flush, and concurrent cancellation/department admission.

Discharge cancellation tests cover retained discharge records, restoration with
or without a bed, multiple cancellation cycles, equal transfer/discharge times,
inactive references, rollback after flush, and concurrent cancellation,
readmission, and bed assignment. These locking checks use H2 at `READ_COMMITTED`;
they do not establish behavior on a different database or isolation level.

History tests cover backdated admissions and bed assignments, exact boundaries
across UTC offsets, cancelled admissions, zero-duration locations, failed transfers
and cancellations, and waiting workflows reading newly committed closed history.
