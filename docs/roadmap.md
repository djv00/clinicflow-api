# Delivery roadmap

Updated: 2026-09-13. This describes implemented behaviour and the next delivery
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
| Inpatient workbench | In progress | Admission and department entry are connected to the page, with active location choices and optional bed assignment. Transfer, discharge, and correction actions still require API calls or the demo script. |
| Authentication and roles | Planned | No login or server-identified operator yet. |
| Deployment and interview walkthrough | Planned | Local instructions and API examples exist; a hosted demo and a concise architecture/business walkthrough remain. |

## Remaining delivery sequence

1. **Complete the inpatient workbench.** Admission and department entry from the
   patient record are implemented, reusing the existing endpoints and conflict rules.
   Next add transfer and discharge actions, an inpatient list, and readable
   encounter history. Add correction
   actions with the same rules already enforced by the backend.
2. **Add authenticated operations.** Define the small set of operator roles,
   protect both pages and APIs, and record the operator from the authenticated
   identity. Verify permission failures as well as successful workflows.
3. **Package the demonstration.** Make persistent demo setup repeatable, document
   configuration and deployment, and prepare an English walkthrough of the
   workflow, transaction boundaries, concurrency behaviour, tests, and tradeoffs.

Each step should be delivered through small, runnable commits. Stop for review
and a commit after a tested slice, rather than accumulating the whole workbench.
The next slice is transfer from an encounter that is already in a department,
showing its current placement and selecting the destination.

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
