# Inpatient physician assignments

## Scope and delivery status

The persistence slice is implemented: an assignment links an encounter, physician,
and service department, with effective start/end times and operator audit. The
directory's many-to-many department affiliations describe where a physician can
work; an assignment describes responsibility for one particular hospital stay.

There are no assignment endpoints or page controls yet. Existing admission,
transfer, and discharge services do not create or close these records. The next
slice must connect the workflows together before exposing assignment writes.

The workflow references establish that department entry and transfer identify a
responsible inpatient physician. They do not fully specify concurrency, doctor
handover, or discharge-correction behaviour. The rules below are ClinicFlow design
decisions for the next slice, not claims about another system's implementation.

## Planned workflow rules

| Operation | Intended behaviour |
| --- | --- |
| First assignment | The encounter must be in a department. Select an active physician affiliated with that active department. Record the authenticated operator. |
| Physician handover | Close the previous assignment as `REASSIGNED` and insert the next one in the same transaction. Keep both records. |
| Release without replacement | Close the assignment as `RELEASED`; the encounter remains in care and explicitly unassigned. |
| Bed or ward change within the same department | Keep the physician assignment. Its responsibility is tied to the department, not a particular bed. |
| Department change | Close the previous department's assignment as `DEPARTMENT_TRANSFER`. A physician for the receiving department must be selected explicitly, even if the previous physician serves both departments. |
| Discharge | Close any open assignment as `DISCHARGE` at the effective discharge time in the same transaction. |
| Cancel discharge | Preserve the closed assignment and its audit. Restore the encounter/location under existing rules, leaving physician selection explicit. Never silently reopen a historic assignment or block a location correction because a physician has since become inactive. |
| Cancel admission | Existing rules allow this only before department care. Physician assignment is unavailable at that stage. |
| Directory affiliation changes or deactivation | Preserve all assignment records. Eligibility changes govern new assignments; they do not rewrite past responsibility. |

V1 has one responsible inpatient physician at a time per encounter. This is a
deliberate scope limit, not a claim that a hospital patient has only one doctor.
Consultants, care-team hierarchies, credential checks, and physician logins are
outside this slice. A physician can be responsible for several encounters.

After a discharge correction, a new assignment may start at the restored care
period's start only after an operator confirms it and eligibility/history checks
pass. Until then, the encounter must be shown as unassigned.

## Data and transaction rules

- `EncounterPhysicianAssignment` is an association entity with three lazy
  `ManyToOne` references and no cascading to shared records. The encounter,
  physician, department, start time, and assigning operator are immutable through
  this entity. Closing it records the end time, reason, and ending operator.
- Effective times represent responsibility, not when the request was received.
  End time cannot precede start time. A zero-duration record is retained when two
  changes have the same effective timestamp; record IDs only break display ties.
- PostgreSQL enforces one open assignment per encounter with a partial unique
  index. Closed history is retained. Foreign keys prevent deleting referenced
  physicians, encounters, or departments; there is no cascading history deletion.
- The close fields must be all absent or all present. A version column protects
  against stale updates to the same assignment. H2 repository tests validate the
  mapping; PostgreSQL tests separately validate the migration and database checks.
- The next service slice must serialize assignment changes with the existing
  encounter write lock, reject overlap/backdated changes that cross later history,
  and enforce current-location and physician eligibility. These cross-record rules
  are not enforced by the persistence slice alone.
- Replacement must flush the previous closure before inserting a new open row,
  because the PostgreSQL unique index is immediate. Both operations must roll back
  together on failure. Authenticated operator names must come from the session,
  never from a client-supplied audit field.
