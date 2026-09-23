# Delivery roadmap

Updated: 2026-09-23. This describes implemented behaviour and the next delivery
steps; planned items are not claims about existing features.

The target is a demonstrable Java inpatient workflow application: register a
patient, admit them, assign a location, transfer, discharge, and inspect the
history. Business rules are implemented in one Spring Boot backend.

## Current position

| Area | Status | Available behaviour |
| --- | --- | --- |
| Core patient flow | Implemented | Registration, admission, department entry, transfer, discharge, admission cancellation, discharge cancellation, and timeline APIs. |
| Persistence and verification | Implemented | PostgreSQL profile, Flyway migration, H2 tests, PostgreSQL tests, and GitHub Actions configuration. |
| Patient workbench | Implemented | Registration, search, pagination, details, paginated hospital encounter history, and hospital admission from the patient record. |
| Inpatient workbench | Implemented | Admission, department entry, transfer, discharge, both cancellation workflows, and timeline are connected to the page. The inpatient list supports search, status/current-location filters, pagination, and opening patient records. |
| Authentication and roles | Implemented | Session login/logout, operator/viewer clinical permissions, directory administrator permissions, CSRF protection, authenticated cancellation operators, and PostgreSQL account persistence with initial provisioning. Accounts have one role each. Account administration and password reset are not implemented. |
| Persistent demo setup | Implemented | An explicit PostgreSQL setting initializes fictional departments, wards, and beds transactionally. Repeated startup preserves existing references and patient history. |
| Physician directory | API and page implemented | Paginated keyword/department/active filters, details, creation, profile/affiliation updates, activation/deactivation, and administrator-only maintenance. The page supports read-only roles, version-conflict recovery, and checking an unconfirmed save. |
| Encounter physician responsibility | API implemented | Assignment, handover, release, eligibility and stale-selection checks, history, and atomic transfer/discharge closure. The query returns current location and responsibility together. Writes use session operators; viewers can read. Page controls are the next slice. |
| Deployment and interview walkthrough | Planned | Local instructions and API examples exist; a hosted demo and a concise architecture/business walkthrough remain. |

## Remaining delivery sequence

1. **Expose physician responsibility in the workbench.** Persistence, workflow
   services, and secured assignment/history/release APIs are in place. Next, add
   physician selection, handover, release, and history controls on the encounter page.
   Follow the [assignment workflow rules](physician-assignments.md),
   including explicit physician selection after a discharge correction.
2. **Package deployment.** Provide a repeatable application-and-database startup,
   document configuration, and verify the demonstration in its target environment.
3. **Prepare the interview walkthrough.** Explain the workflow, transaction
   boundaries, concurrency behaviour, tests, and tradeoffs in a concise English demo.

Each step should be delivered through small, runnable commits after a tested slice,
rather than accumulating the whole feature before committing.
The core workbench flow, session login, viewer/operator permissions, and
authenticated cancellation operators are connected. PostgreSQL now preserves
accounts across restarts; initial passwords only provision missing accounts.
Fictional PostgreSQL locations are available through `CLINICFLOW_DEMO_DATA_ENABLED`;
the setting is off by default and is separate from the H2 `demo` profile.

## Later extensions

Physician records apply coursework relationship modelling to the inpatient
workflow. Keep the existing coursework repository unchanged. Physician codes are
immutable, case-sensitive identifiers with surrounding whitespace removed.
Affiliations refer to existing departments, are unique per physician/department,
and have no cascade to shared department records. Deactivation keeps affiliations.
New affiliations require active departments; existing inactive affiliations can
be retained or removed. New encounter assignments require an active physician
affiliated with the encounter's current active department.
Version checks also cover affiliation edits, so a stale profile cannot replace a
more recent department selection.

A limited medicine catalogue and encounter medication orders can follow the
physician workflow. Billing, outpatient scheduling, and coursework training or
certificate modules remain outside this delivery sequence. Link physician records
to user accounts only when a physician-specific login use case is implemented.

## Verification entry points

- `mvnw.cmd test` runs the regular unit, controller, and H2 tests.
- Physician repository checks cover shared departments, duplicate affiliations
  across persistence contexts, removal without cascading, deactivation, unique
  codes, and stale updates. PostgreSQL checks add a V2-to-V3 upgrade with existing
  data, database constraints, affiliation rollback, and competing edit versions.
- Directory API checks cover real administrator login, role separation, CSRF,
  pagination without duplicate physicians, literal search, version conflicts,
  inactive references, and atomic profile changes. PostgreSQL checks include the
  V4 role-constraint upgrade without changing existing account IDs/passwords,
  administrator persistence across restarts, filtered queries, and a controlled
  race between two registrations using the same physician code.
- Assignment persistence checks cover responsibility history, shared physicians,
  retained department references after directory changes, no cascading deletion,
  and optimistic locking. PostgreSQL checks cover V4-to-V5 migration with existing
  data, closure/time/reference constraints, one open assignment under concurrent
  inserts, and rollback of both sides of a handover.
- Assignment service checks cover stale selections, eligibility, responsibility
  times, same-department moves, department changes, discharge correction, and
  rollback of complete workflows. PostgreSQL checks verify competing selections,
  assignment versus discharge in both orders, and eligibility after a concurrent
  directory edit. Existing transfer/discharge endpoint checks verify that closure
  audit uses the session operator and ignores a forged operator in request JSON.
- Assignment API checks cover real login and canonical operator names, read/write
  roles, CSRF, field validation, missing references, eligibility failures, stale
  location/assignment IDs, and repeated submissions. Flat response DTOs work with
  Open Session in View disabled. A PostgreSQL concurrency check verifies that a
  query waits for a discharge and returns matching encounter/location/physician state.
- Physician page checks cover administrator navigation, multiple department
  selection, profile edits, deactivation/reactivation, read-only roles, and
  unsaved-edit confirmation. Two open records verify stale-edit rejection and
  reloading. Interrupted successful responses verify that updates are reloaded
  and unconfirmed creations are looked up by their exact code before another save.
  Browser checks also cover combined filters, pagination, empty results, failed
  list requests and retry, failed permission loading, and narrow-screen overflow.
- `mvnw.cmd -Ppostgres-it clean verify` also runs the PostgreSQL tests. These
  require a working Docker runtime or the `TEST_DATABASE_*` connection settings
  described in the [README](../README.md#postgresql-integration-tests).
- IDEA's JUnit run-all action can include `PostgresWorkflowIT`,
  `PostgresAccountsIT`, and `PostgresDemoDataIT` directly. They still need that
  database environment; IDEA does not follow Maven's default test-file selection.
- Browser checks cover empty and populated records, pagination, cancelled
  admissions, and switching between patients. Admission checks cover successful
  creation, conflict and validation messages, local time conversion, duplicate
  submit prevention, cancellation without saving, and recovery after failed requests.
- Department entry checks cover bed and no-bed saves, ward changes, stale bed
  results, occupied-bed conflicts, entry time validation, unavailable dictionaries,
  and rechecking an encounter after an unconfirmed save.
- Transfer checks cover retaining the current bed, changing wards, releasing a bed,
  unchanged destinations, transfer time validation, occupied-bed conflicts, changed
  source placements, and recovery after a saved transfer's response is lost.
- Discharge checks cover bed and no-bed stays, time validation, changed placements,
  already discharged encounters, failed reads, duplicate-submit prevention, response
  loss, and the persisted location closure, bed release, and discharge record.
- Timeline checks cover empty histories, cancelled admissions, transfers,
  discharge/cancellation cycles, equal-time records, missing location names,
  failed requests, refresh, and switching encounters while a request is pending.
- Inpatient query checks cover current versus historical placements, combined filters,
  optional beds, inactive references, literal search, pagination, and workflow changes.
  Browser checks cover filtering, patient record actions, refreshed results, failed reads,
  and stale requests. PostgreSQL checks include the joined worklist query and page count.
- Admission cancellation page checks cover time validation, leaving without
  saving, failed reads and refresh, changing encounter state, blocked duplicate submissions,
  and recovery after a committed cancellation's response is lost. The cancelled encounter
  remains in the patient record and timeline and leaves the inpatient list.
- Discharge cancellation page checks cover original placement previews, bed and no-bed
  restoration, time validation, leaving without saving, occupied beds,
  intervening bed use, another active stay, failed reads, stale forms, duplicate-submit
  prevention, and recovery after a committed correction's response is lost. Timeline and
  inpatient list checks confirm the restored care period. H2 and PostgreSQL tests verify
  that a stale discharge ID is rejected even when a new discharge has the same timestamp.
- Session security checks cover anonymous reads/writes, wrong credentials, login and
  business-write CSRF checks, session fixation protection, token rotation, authenticated
  reads/writes, logout invalidation, no-store responses, and password hashing. Existing
  business API tests now provide an authenticated test user and CSRF tokens while
  keeping the security filters enabled.
- Browser checks cover incorrect credentials, successful sign-in, patient registration,
  inactivity expiry, sign-out, and a second tab attempting to use the signed-out session.
  The authenticated demo script completes the inpatient workflow and signs out.
- Role checks cover all seven business POST endpoints, other write methods,
  an unrecognised role, distinct CSRF/permission failures, and a real viewer login,
  reads, forbidden registration, and logout. Account configuration checks cover
  optional viewer activation, BCrypt, and duplicate/blank usernames.
- Browser role checks cover read-only records in admitted, in-department,
  discharged, and cancelled states, timeline access, inpatient filtering, and
  operator action visibility. A failed session-permissions request keeps editing
  hidden in the directory and patient record; reloading restores operator access.
  The role slice passed 355 regular tests, 8 PostgreSQL tests, and the authenticated
  demo workflow.
- Authenticated cancellation checks cover missing/forged request operators,
  different signed-in operators, canonical account names after real login,
  persisted timeline values, and repeated cancellations preserving the original
  audit. Operator account names are checked against the audit field length at
  startup. Browser checks confirm both forms save without an operator input,
  admission-time validation still works, and discharge restoration shows the
  authenticated operator in the timeline. This slice passed 357 regular tests,
  8 PostgreSQL tests, and the updated authenticated demo workflow.
