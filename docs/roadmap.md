# Delivery roadmap

Updated: 2026-09-12. This describes implemented behaviour and the next delivery
steps; planned items are not claims about existing features.

The target is a demonstrable Java inpatient workflow application: register a
patient, admit them, assign a location, transfer, discharge, and inspect the
history. Business rules are implemented in one Spring Boot backend.

## Current position

| Area | Status | Available behaviour |
| --- | --- | --- |
| Core patient flow | Implemented | Registration, admission, department entry, transfer, discharge, admission cancellation, discharge cancellation, and timeline APIs. |
| Persistence and verification | Implemented | PostgreSQL profile, Flyway migration, H2 tests, PostgreSQL tests, and GitHub Actions configuration. |
| Patient workbench | Implemented | Registration, search, pagination, details, and paginated hospital encounter history with current status. |
| Inpatient workbench | Next | The write APIs exist, but admission and location changes still require API calls or the demo script. |
| Authentication and roles | Planned | No login or server-identified operator yet. |
| Deployment and interview walkthrough | Planned | Local instructions and API examples exist; a hosted demo and a concise architecture/business walkthrough remain. |

## Remaining delivery sequence

1. **Complete the inpatient workbench.** Start with an admission form in the
   patient record, reusing the existing admission endpoint and its active-stay
   conflict rules. Then add an inpatient list, department/ward/bed selection,
   transfer and discharge actions, and readable encounter history. Add correction
   actions with the same rules already enforced by the backend.
2. **Add authenticated operations.** Define the small set of operator roles,
   protect both pages and APIs, and record the operator from the authenticated
   identity. Verify permission failures as well as successful workflows.
3. **Package the demonstration.** Make persistent demo setup repeatable, document
   configuration and deployment, and prepare an English walkthrough of the
   workflow, transaction boundaries, concurrency behaviour, tests, and tradeoffs.

Each step should be delivered through small, runnable commits. Stop for review
and a commit after a tested slice, rather than accumulating the whole workbench.
The next slice after patient encounter history is the admission form.

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
  admissions, failed requests, and switching between patients.
