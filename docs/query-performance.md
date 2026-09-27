# Query measurement and patient history index

Measured on 2026-09-27 with Java 21 and PostgreSQL 17.11. This is a repeatable
synthetic-data experiment, not a production throughput or HTTP latency claim.

## What changed

V8 adds one B-tree index on `encounters`:

```sql
CREATE INDEX idx_encounters_patient_history
    ON encounters (patient_id, admitted_at DESC, encounter_number DESC);
```

Patient history filters by one patient and orders by admission time and encounter
number, newest first. Before V8, both the page query and count scanned all 151,000
encounters to find that patient's 31 stays. The new index supports the equality
filter and page ordering. The repository query, response, pagination contract,
and business rules are unchanged.

The new index occupied 8,912,896 bytes (8.5 MiB) in this fixture. It adds storage
and index maintenance to encounter writes. It is an ordinary transactional Flyway
index build, so schedule upgrades with writers stopped; no zero-downtime migration
is claimed. Older migrations remain unchanged.

## Dataset and method

`src/test/resources/query-plans/fixture.sql` contains only fictional data:

- 5,000 patients, each with 30 closed stays, plus 1,000 active stays: 151,000 encounters.
- 150,000 closed locations and discharge records, plus 800 open locations; 200
  active stays are waiting for department entry. Some open locations have no bed.
- 20 departments, 10 wards, 1,000 beds, and 1,000 physicians with two affiliations each.

`PostgresQueryPlansIT` creates a randomly named schema, migrates it to V7, seeds
and analyzes these rows, and calls the actual services. The test-only
`QueryCapture` records prepared SELECTs and JDBC parameter setters on the calling
thread; it does not reconstruct SQL from JPQL or install a production interceptor.

The test replays those statements and bindings through
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`, warms each query once, and records five
samples. It then applies V8 to the same data and repeats the measurements. Complete
service responses must remain equal across the upgrade, and a second migration
run must do nothing. Reports include SQL, bindings, every sample, PostgreSQL
version/settings, and index sizes.

The local settings were 128 MiB shared buffers, 4 MiB work memory, random page cost
4, effective cache size 4 GiB, and statistics target 100. EXPLAIN uses
`force_custom_plan` inside a rolled-back measurement transaction. This measures
parameter-specific plans, not generic prepared-plan selection, cold-cache I/O,
concurrent load, end-to-end requests, or every possible data distribution. The
application's planner settings are not changed. PostgreSQL explains the distinction
in its [prepared statement documentation](https://www.postgresql.org/docs/17/sql-prepare.html).

## Observed work and SQL counts

The values below are median shared-buffer accesses (hits plus reads) from five
samples. They count accesses, including repeated hits, not distinct disk pages.
Interpret them with the plan tree, as described in the
[PostgreSQL EXPLAIN guide](https://www.postgresql.org/docs/17/using-explain.html).

| Patient-history statement | V7 | V8 | Plan change |
| --- | ---: | ---: | --- |
| Patient existence lookup | 3 | 3 | Primary-key lookup retained. |
| First 20 stays | 2,013 | 23 | Full scan and sort becomes an ordered index scan. |
| Total stay count | 2,013 | 34 | Full scan becomes a bitmap scan of matching index entries. |
| Remaining 11 stays | 2,013 | 34 | Full scan becomes a small bitmap scan and sort. |

There is no timing threshold in CI. The assertions check results and SQL counts;
the reports make plan and timing changes inspectable without failing a build
because a runner is busy or PostgreSQL chooses a different valid plan.

| Full first page, 5 or 20 results | SELECT count | Reason |
| --- | ---: | --- |
| Inpatient list | 2 | DTO page and count. |
| Patient history | 3 | Patient lookup, encounter page, and count. |
| Physician directory | 3 | Physician page, count, and one batch affiliation fetch. |

No per-result SQL growth was observed between the two page sizes. Physician
pagination stays in SQL and returns both affiliations, including when filtering
by just one department. A collection fetch that silently paginates in memory is
configured to fail this test. Short or empty pages may skip the count query;
these figures are not a promise that every request issues exactly that many SQLs.

## Why the other queries were left unchanged

The inpatient cases cover no filters, department, ward, their combination, waiting
status, and a literal keyword. Existing V7 partial indexes already restrict the
worklist to active encounters and open locations. Physician cases cover the full
directory and active physicians in one department. The measured query counts and
plans did not justify changing their current projection/batch-fetch approach or
adding speculative department/ward/physician indexes.

An additional active-stay ordering index was evaluated in the test schema:
`(admitted_at, encounter_number) WHERE status IN ('ADMITTED', 'IN_DEPARTMENT')`.
It made the first worklist page avoid sorting, but changed other plans unfavourably:

| Shared-buffer accesses | V8 alone | With candidate |
| --- | ---: | ---: |
| Unfiltered worklist count | 91 | 1,078 |
| Waiting-status count | 1,019 | 2,006 |
| Literal keyword lookup | 76 | 1,063 |

The candidate is not in V8. The comparison test creates it only in its generated
schema, records `candidate-active-order.json`, and removes it in `finally`. This
keeps the tradeoff reproducible. These particular measurements are insufficient
to establish a net benefit across real workload mixes. Partial-index eligibility
also depends on the predicate being provable at planning time; see the
[PostgreSQL partial-index documentation](https://www.postgresql.org/docs/17/indexes-partial.html).

## Reproduce

Use Java 21 and the Docker or `TEST_DATABASE_*` setup in the
[PostgreSQL test instructions](../README.md#postgresql-integration-tests):

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -Ppostgres-it '-Dit.test=PostgresQueryPlansIT' verify
```

This still runs the regular tests and selects only this PostgreSQL test class.
The class owns and drops only its generated schema. Do not manually load the
fixture into an application schema.

Inspect `target/query-plans/v7.json`, `v8.json`, and
`candidate-active-order.json`. `clean` removes earlier reports. The normal
`mvnw.cmd -Ppostgres-it clean verify` runs this comparison alongside all other
PostgreSQL tests, and GitHub Actions includes the reports in its test artifact.
