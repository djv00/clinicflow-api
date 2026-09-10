# API reference

[Back to the project README](../README.md)

The base path is `/api/v1`. Requests with bodies use `application/json`.
Resource IDs are UUIDs; medical record numbers and encounter numbers are separate
caller-supplied identifiers. Replace `<patientId>` and `<encounterId>` below
with IDs returned by earlier requests. Use fresh record numbers when repeating
the examples.

Operation timestamps include an offset and cannot be in the future. The examples
use past dates and assume a newly registered patient. Location IDs shown here
come from the [demo fixtures](../src/main/resources/demo/data.sql); query the
reference endpoints when using another database. The
[demo script](../scripts/demo-workflow.ps1) runs the complete workflow with new
record numbers and current UTC timestamps.

## Endpoints

| Method | Path after `/api/v1` | Success | Response |
| --- | --- | --- | --- |
| POST | `/patients` | 201 | Patient |
| GET | `/patients/{id}` | 200 | Patient |
| POST | `/encounters` | 201 | Encounter |
| GET | `/encounters/{id}` | 200 | Encounter |
| POST | `/encounters/{id}/department-admissions` | 201 | Location |
| POST | `/encounters/{id}/transfers` | 201 | Location |
| POST | `/encounters/{id}/admission-cancellations` | 200 | Encounter |
| POST | `/encounters/{id}/discharges` | 200 | Encounter |
| POST | `/encounters/{id}/discharge-cancellations` | 200 | Encounter |
| GET | `/encounters/{id}/discharges` | 200 | Discharge list |
| GET | `/encounters/{id}/timeline` | 200 | Encounter, locations, discharges |
| GET | `/departments` | 200 | Department list |
| GET | `/departments/{id}` | 200 | Department |
| GET | `/wards` | 200 | Ward list |
| GET | `/wards/{id}` | 200 | Ward |
| GET | `/beds` | 200 | Bed list |
| GET | `/beds/{id}` | 200 | Bed |

## Patient registration

```http
POST /api/v1/patients
Content-Type: application/json

{
  "medicalRecordNumber": "DEMO-DOC-001",
  "firstName": "Demo",
  "lastName": "Patient",
  "dateOfBirth": "1990-05-14"
}
```

Returns `201 Created` with `id` and the four registration fields.
`GET /api/v1/patients/{id}` returns the same response shape.

The medical record number is required and limited to 50 characters; first and
last names are required and limited to 100 characters each. Date of birth must
be a date in the past. Invalid fields return `400`; an already registered
medical record number returns `409`; an unknown patient ID returns `404`.

## Hospital admission

```http
POST /api/v1/encounters
Content-Type: application/json

{
  "patientId": "<patientId>",
  "encounterNumber": "DEMO-DOC-VISIT-001",
  "admittedAt": "2025-09-01T09:00:00-04:00"
}
```

Returns `201 Created` with `id`, `encounterNumber`, `patientId`,
`status: "ADMITTED"`, `admittedAt`, `dischargedAt`,
`admissionCancelledAt`, and `admissionCancelledBy`. The last three fields are
initially null. `GET /api/v1/encounters/{id}` returns the same response shape.

The patient must exist. The encounter number is required, globally unique, and
limited to 50 characters. A patient may have only one active encounter
(`ADMITTED` or `IN_DEPARTMENT`). Admission cannot predate the end of a
previously discharged encounter for that patient. Cancelled admissions are
retained but do not prevent a new admission.

Invalid fields or future times return `400`; an unknown patient returns `404`;
a duplicate encounter number, active encounter, or history conflict returns
`409`.

## Department admission

```http
POST /api/v1/encounters/<encounterId>/department-admissions
Content-Type: application/json

{
  "departmentId": "10000000-0000-0000-0000-000000000001",
  "wardId": "20000000-0000-0000-0000-000000000001",
  "bedId": "30000000-0000-0000-0000-000000000001",
  "startedAt": "2025-09-01T09:30:00-04:00"
}
```

Returns `201 Created` with a location: `id`, `encounterId`, `departmentId`,
`wardId`, `bedId`, `startedAt`, and `endedAt: null`. The encounter becomes
`IN_DEPARTMENT`.

The encounter must be `ADMITTED` with no open location. Department and ward
are required and must be active. `bedId` may be omitted or null; when supplied,
the bed must be active, belong to that ward, and have no current or historical
occupancy conflict. `startedAt` cannot precede hospital admission.

Unknown references return `404`. Invalid fields, times, inactive references,
or a bed in another ward return `400`. Invalid encounter state, an existing
open location, or an occupancy conflict returns `409`.

## Transfer

```http
POST /api/v1/encounters/<encounterId>/transfers
Content-Type: application/json

{
  "departmentId": "10000000-0000-0000-0000-000000000002",
  "wardId": "20000000-0000-0000-0000-000000000002",
  "bedId": "30000000-0000-0000-0000-000000000002",
  "transferredAt": "2025-09-02T10:00:00-04:00"
}
```

Returns `201 Created` with the new location in the same shape as department
admission. The encounter remains `IN_DEPARTMENT`. The previous location ends
and the next one starts at `transferredAt`; both changes commit together.

The encounter must have a current location. The destination must change at least
one of department, ward, or bed. Department and ward remain required; `bedId`
may be omitted or null. The time cannot precede the current location's start.
An unchanged location returns `409`. Destination validation and occupancy
conflicts use the same `400`, `404`, and `409` categories as department
admission.

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

Departments and wards are separate references in the current model. There is no
department-to-ward mapping or department filter on wards and beds. A bed belongs
to one ward, and assignments validate that ward against the requested `wardId`.

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

These creation endpoints do not support importing complete historical encounters
out of order or editing existing location intervals.

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

## Errors

Business exceptions and bean-validation failures return Spring `ProblemDetail`
responses with `title`, `status`, `detail`, and `instance`.
Bean-validation errors also include an `errors` object keyed by request field.
For example, registering a patient with an empty medical record number returns:

```json
{
  "title": "Invalid request",
  "status": 400,
  "detail": "Request validation failed",
  "instance": "/api/v1/patients",
  "errors": {
    "medicalRecordNumber": "Medical record number is required"
  }
}
```

Malformed JSON, UUIDs, query parameters, and timestamps return `400`; they are
handled by Spring MVC and may have a different response body. Clients should
check the HTTP status before reading error-specific fields.
