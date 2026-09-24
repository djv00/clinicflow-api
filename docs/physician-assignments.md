# Inpatient physician assignments

## Scope and delivery status

Persistence and workflow services are implemented: an assignment links an encounter, physician,
and service department, with effective start/end times and operator audit. The
directory's many-to-many department affiliations describe where a physician can
work; an assignment describes responsibility for one particular hospital stay.

`EncounterPhysicianService` supports assignment, handover, release, and history
reads. Transfer and discharge close responsibility in their existing transaction,
using the authenticated operator from those endpoints. Department entry leaves
physician selection explicit. Secured assignment, release, and combined state/history
endpoints are available; see the [API examples](api.md#physician-responsibility).
The patient and inpatient workbench provides assignment, handover, release, and
history controls through **Physician responsibility**.

The workflow references establish that department entry and transfer identify a
responsible inpatient physician. They do not fully specify concurrency, doctor
handover, or discharge-correction behaviour. The rules below are ClinicFlow design
decisions, not claims about another system's implementation.

## Implemented service rules

| Operation | Behaviour |
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
pass. Until then, no open physician assignment exists. The API returns a null
`currentAssignmentId`; the page displays that unassigned state explicitly.

Assign/release operations require the caller's last-seen location ID and current
assignment ID. A null assignment ID means the caller saw no current physician;
it does not mean an unconditional overwrite. A changed location or assignment,
reselecting the current physician, or releasing an absent assignment is a conflict.
The endpoints take the operator from the authenticated session. Client-supplied
audit fields cannot replace it. Viewers and operators can read responsibility;
only operators can assign or release. Directory administrator permission alone
does not grant access to encounter responsibility.

The combined query returns encounter status, current location, current assignment
ID, and responsibility history under one encounter read lock. Clients should use
that context for write preconditions. A missing current location is normal before
department entry and after discharge; a missing current assignment is normal while
physician selection is pending. Physician and department display names reflect
their current directory values; the responsibility record retains the reference
IDs, effective times, and operators, not a snapshot of every directory field.

## Workbench behaviour

- Open responsibility from an inpatient row or any encounter in a patient record.
  Operators can change responsibility while the encounter is in a department;
  viewers can inspect it. Before department entry and after closure, history remains
  available without write controls.
- The picker searches active physicians affiliated with the current department and
  supports pagination. The current physician cannot be selected as their own replacement.
  An inactive department prevents new assignments but still permits release.
- The form shows the proposed change and local effective time. Handover ends the
  previous period and starts the new period at that same time. Release leaves the
  patient in care without a responsible physician.
- Saves use the displayed location and assignment as preconditions. A conflict or
  uncertain response blocks further saves until responsibility is refreshed. Refresh
  clears the unfinished form, so a replacement must be selected again. No write is
  replayed automatically. A confirmed save followed by a failed refresh remains
  labelled as saved, with a separate refresh error.
- History retains effective times, closure reasons, and recorded operators. Directory
  names are current values. The page identifies the current assignment by its ID,
  rather than assuming the final row is current.

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
- Assignment changes use the existing encounter write lock. New assignments and
  releases cannot precede the current location start, the open assignment start,
  or the end of any closed responsibility record, and cannot be in the future.
  Transfer to another department and discharge also reject times before recorded
  responsibility, including when the current physician has already been released.
- Assignment eligibility is held stable with read locks in the order Encounter ->
  Physician -> Department. A directory edit's version update locks the physician
  before changing affiliations. Assignment waits for such an edit and checks its
  committed result. Directory changes made after an assignment commits preserve
  that assignment; they do not terminate an ongoing responsibility automatically.
- Replacement must flush the previous closure before inserting a new open row,
  because the PostgreSQL unique index is immediate. Both operations must roll back
  together on failure. Authenticated operator names must come from the session,
  never from a client-supplied audit field.

## Verification

Service integration tests cover handover/release, stale selections, eligibility,
effective times, department changes, discharge correction, rollback after flushed
writes, and session-derived closure operators. PostgreSQL tests additionally
exercise competing selections, assignment/discharge in both lock orders, and
assignment waiting for physician deactivation or affiliation removal. H2 does not
provide the PostgreSQL partial index; workflow writes still use the encounter lock.
API tests cover real session audit, permissions, CSRF, invalid bodies, detached
response mapping, stale forms, repeated requests, and discharge/correction reads.
The PostgreSQL query test verifies a consistent response during concurrent discharge.
