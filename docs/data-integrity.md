# Inpatient data integrity

The service layer coordinates care changes and returns business errors. PostgreSQL
also enforces the following invariants, including for writes that bypass the
application. Flyway V7 adds these rules to the existing tables; V1 is unchanged.

| Rule | PostgreSQL enforcement |
| --- | --- |
| A patient has at most one active encounter. | `uk_encounters_active_patient`: unique `patient_id` where status is `ADMITTED` or `IN_DEPARTMENT`. |
| An encounter has at most one current location. | `uk_encounter_locations_open_encounter`: unique `encounter_id` where `ended_at IS NULL`. |
| A bed has at most one current occupant. | `uk_encounter_locations_open_bed`: unique non-null `bed_id` where `ended_at IS NULL`. |
| A location cannot end before it starts. | `ck_encounter_locations_time`: end is null or end is on/after start. |

Discharged and cancelled encounters remain in history. Multiple patients may have
open locations without beds. Closed locations may reference the same bed, and
zero-duration locations are valid for equal-time changes or corrections.

These are current-state constraints, not a prohibition on overlapping historical
intervals. Service checks still handle backdated occupancy, admission history,
state transitions, active reference data, and stale request IDs. The constraints
also do not require every active encounter to have a location: `ADMITTED` patients
may still be waiting for department entry. No cross-table trigger is introduced.

## Transactions and write order

Admission and discharge correction lock the patient to coordinate active stays.
Location changes lock the encounter, then the destination bed if one is selected.
These locks retain the existing business conflict responses; database constraints
provide an additional boundary if a write omits the service checks.

During transfer, Hibernate normally executes queued inserts before updates. A new
open location would therefore conflict with the old one even though the service
has already ended the old entity in memory. The service now flushes the old
location closure before saving its replacement. This also permits a department
change that retains the same bed.

Flush sends SQL but does not commit. Closing physician responsibility, ending the
old location, and inserting the new location remain in one transaction. A later
failure rolls all of them back. Discharge correction similarly restores the
encounter, adds a location, and records cancellation audit atomically. It does not
automatically restore the previous physician.

## Upgrading existing data

V7 checks for violations before adding the indexes and check constraint. It fails
with a specific message if legacy data is inconsistent. It does not pick a record
to keep, merge encounters, or change history. PostgreSQL/Flyway runs the migration
transactionally; failure leaves the data and schema at V6.

The migration scans existing records and builds indexes using ordinary DDL. Run
the upgrade during a maintenance window with application writers stopped; it is
not an online, zero-downtime migration.

If startup reports a V7 data conflict, these read-only queries locate the affected
records in the application's schema:

```sql
SELECT patient_id, array_agg(id ORDER BY id) AS encounter_ids
FROM encounters
WHERE status IN ('ADMITTED', 'IN_DEPARTMENT')
GROUP BY patient_id HAVING count(*) > 1;

SELECT encounter_id, array_agg(id ORDER BY id) AS location_ids
FROM encounter_locations
WHERE ended_at IS NULL
GROUP BY encounter_id HAVING count(*) > 1;

SELECT bed_id, array_agg(id ORDER BY id) AS location_ids
FROM encounter_locations
WHERE ended_at IS NULL AND bed_id IS NOT NULL
GROUP BY bed_id HAVING count(*) > 1;

SELECT id, encounter_id, started_at, ended_at
FROM encounter_locations
WHERE ended_at < started_at;
```

Review those records against the actual care history before correcting them and
retrying startup. Do not delete records merely to make the migration pass.

## Verification

`PostgresInpatientIntegrityIT` verifies V6-to-V7 migration with retained IDs and
history, safe refusal of each invalid legacy case, insert/update constraints,
optional beds, and equal-time history. Coordinated independent transactions verify
all three unique indexes: PostgreSQL reports the second write waiting, then it
either rejects it after the first commit or permits it after the first rollback.

Service checks cover department transfers with the same bed, a different bed, or
no bed; physician closure and rollback after SQL reaches the database; discharge
correction rollback; and readmission after keys have been released. Existing
PostgreSQL workflow and demo-data tests also run against V7.

The default H2 profiles still use Hibernate-generated tables and service rules.
They do not install these PostgreSQL partial indexes; use `-Ppostgres-it` to verify
the database guarantees. Indexes here enforce correctness; no query-speed or
throughput improvement is claimed.
