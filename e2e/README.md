# Browser regressions

Run from the repository root with Java 21 and Node.js 22 or later:

```powershell
.\mvnw.cmd --batch-mode --no-transfer-progress -DskipTests package
npm.cmd --prefix e2e ci
cd e2e
npx.cmd playwright install chromium
npm.cmd test
```

On Linux/macOS use `./mvnw`, `npm`, and `npx`. On a Linux CI runner, install browser
OS dependencies with `npx playwright install --with-deps chromium`.
Set `JAVA_HOME` to your JDK 21 installation if the default Java is older.

Playwright starts the packaged application on `127.0.0.1:18081` with the `demo`
profile, its own in-memory H2 database, and fictional operator/viewer/administrator
credentials. It stops the process after the run. An occupied port fails the run;
an existing application is never reused. Spring/deployment environment settings
are excluded from the child process. Keep these test credentials out of any
deployed instance. Tests do not read or alter a development PostgreSQL database.

Rebuild the jar after changing Java, HTML, JavaScript, or application resources.
Each test has a separate browser context and unique records created through real
authenticated APIs. Tests run with one worker and no automatic retries.
The first workflow is driven entirely through the page; focused regression tests
use API setup for their starting state and re-read persisted results after writes.

`npm test -- --grep "viewer"` runs a selected scenario. `npm run test:headed`
opens Chromium visibly when inspecting a failure. Failed tests retain a screenshot
and Playwright trace under `test-results`; `npx playwright show-report` opens the
HTML report. These ignored reports contain only fictional test data and local
test sessions, but should still be treated as test artifacts.

These checks exercise Chromium against H2. PostgreSQL locking, migrations, and
constraints remain covered by `mvnw -Ppostgres-it clean verify`; browser tests
do not replace that suite or establish cross-browser compatibility.

## Scenarios

| Area | Automated coverage |
| --- | --- |
| Clinical flow | Register, admit, occupy a bed, transfer to care without a bed, verify bed release, discharge, and inspect retained location/discharge history. Admission cancellation retains the record and removes it from the worklist. |
| Roles and sessions | Viewer reads without write controls; a write with valid CSRF is still forbidden. Administrators maintain the directory without clinical access. Failed permission reads hide writes. Signing out invalidates another tab. |
| Physician directory | Multiple affiliations, profile changes, deactivation without deleting departments, stale-edit rejection/reload, and recovery of an unconfirmed creation by exact code. |
| Responsibility | Assign, hand over, release, and retain history. Department transfer and discharge close responsibility. Discharge correction restores care without automatically restoring a physician. Both patient and inpatient entry points are exercised. |
| Lists | Inpatient pagination, combined current-location filters, waiting patients, failed search retry, and cancellation of an earlier search so it cannot replace newer results. |
| Write recovery | A competing transfer after the browser's precheck returns 409 and requires refresh. A discharge commits but its response is lost; the form blocks another save until refresh confirms discharge. |
| Error contract | A real duplicate-admission rejection retains its stable code while its title/detail are reworded; the form still identifies the encounter-number conflict. |

Fault injection uses Playwright routes only for the selected request. Lost-response
tests first forward the write to the real server and wait for success, then drop
the browser response. The transfer conflict uses a separate authenticated API
session to make an actual competing change. No successful business responses are
fabricated. Test records have unique identifiers; the database is discarded when
the test application stops.

GitHub Actions builds the jar and runs both the PostgreSQL suite and browser tests.
Its `test-reports` artifact includes the Playwright HTML report and failure traces.
The configuration deliberately has no retries, so an intermittent failure fails
CI instead of being hidden by a second attempt.

Runner configuration follows Playwright's [web server lifecycle](https://playwright.dev/docs/test-webserver)
and [network interception](https://playwright.dev/docs/network) APIs.
