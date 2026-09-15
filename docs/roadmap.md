# Delivery roadmap

Updated: 2026-09-15. This describes implemented behaviour and the next delivery
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
| Authentication and roles | Planned | No login or server-identified operator yet. |
| Deployment and interview walkthrough | Planned | Local instructions and API examples exist; a hosted demo and a concise architecture/business walkthrough remain. |

## Remaining delivery sequence

1. **Add authenticated operations.** Define the small set of operator roles,
   protect both pages and APIs, and record the operator from the authenticated
   identity. Verify permission failures as well as successful workflows.
2. **Package the demonstration.** Make persistent demo setup repeatable, document
   configuration and deployment, and prepare an English walkthrough of the
   workflow, transaction boundaries, concurrency behaviour, tests, and tradeoffs.

Each step should be delivered through small, runnable commits. Stop for review
and a commit after a tested slice, rather than accumulating the whole workbench.
The core workbench flow is connected. The next stage is authentication and role
checks, starting with login and read access before migrating recorded operators
to authenticated identities and protecting workflow writes.

## Later extensions

Physician records and encounter assignment are a possible coursework-inspired
extension after the core web workflow. Keep the existing coursework repository
unchanged. Billing, prescriptions, outpatient scheduling, and coursework training
or certificate modules are outside this delivery sequence.

## Verification entry points

- `mvnw.cmd test` runs the regular unit, controller, and H2 tests.
- `mvnw.cmd -Ppostgres-it clean verify` also runs the PostgreSQL tests. These
  require a working Docker runtime or the `TEST_DATABASE_*` connection settings
  described in the [README](../README.md#postgresql-integration-tests).
- IDEA's JUnit run-all action can include `PostgresWorkflowIT` directly. It still
  needs that database environment; it does not follow Maven's default test-file
  selection.
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
- Admission cancellation page checks cover time and operator validation, leaving without
  saving, failed reads and refresh, changing encounter state, blocked duplicate submissions,
  and recovery after a committed cancellation's response is lost. The cancelled encounter
  remains in the patient record and timeline and leaves the inpatient list.
- Discharge cancellation page checks cover original placement previews, bed and no-bed
  restoration, time and operator validation, leaving without saving, occupied beds,
  intervening bed use, another active stay, failed reads, stale forms, duplicate-submit
  prevention, and recovery after a committed correction's response is lost. Timeline and
  inpatient list checks confirm the restored care period. H2 and PostgreSQL tests verify
  that a stale discharge ID is rejected even when a new discharge has the same timestamp.
