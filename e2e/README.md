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
