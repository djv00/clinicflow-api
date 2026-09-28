# Run the packaged application

The Compose stack runs the workbench and REST API in one Java 21 container,
with PostgreSQL 17 in a second container. It is intended for a local demonstration
or a single-host deployment behind an HTTPS proxy. Both published ports bind to
loopback by default. This repository does not provision a public host or TLS.

## First startup

Install Docker Desktop with Linux containers and Docker Compose v2. A host JDK,
Maven, Node.js, and Python are not required to start the stack: the Docker build
uses the repository's Maven wrapper and copies the packaged application into a
Java runtime image.

From the repository root in PowerShell:

```powershell
Copy-Item .env.example .env
```

Edit `.env` before starting:

- Set `DB_PASSWORD` and `SPRING_SECURITY_USER_PASSWORD` to your own nonblank values.
- Set the optional viewer and administrator passwords to demonstrate all three
  roles. Usernames default to `operator`, `viewer`, and `admin`.
- Leave `CLINICFLOW_DEMO_DATA_ENABLED=true` for the fictional location dictionary:
  two departments, two wards, and three beds. Patients and physicians are created
  through the workbench. Set the flag to `false` for an empty location dictionary.
- Change `APP_PORT` or `POSTGRES_PORT` if 8080 or 5432 is already occupied.

Do not overwrite an existing `.env` when upgrading. It is ignored by Git and
excluded from the Docker build context. Single-quote values containing `$` or `#`
so Compose treats them literally. Variables already exported in your shell take
precedence over the file.

```powershell
docker compose --profile app config --quiet
docker compose --profile app up --build -d --wait --wait-timeout 180
docker compose --profile app ps
```

Open [http://localhost:8080/](http://localhost:8080/) using the configured app port
and sign in. Administrators land in the physician directory; operators can
register patients and perform care changes; viewers have read-only clinical access.
The app health check calls `/actuator/health`, including database connectivity.
The app starts after the database health check succeeds.

The `app` Compose profile is separate from Spring's profiles. The container uses
only Spring's `postgres` profile; do not add the disposable H2 `demo` profile.
The existing `docker compose up -d --wait postgres` command remains available for
running Java from an IDE against just the containerized database. If you change
`POSTGRES_PORT`, update that IDE's `DB_URL` to use the new host port. The app
container always connects to `postgres:5432` on the internal Compose network.

## Stop, restart and update

```powershell
# Stop processes; keep containers and the database volume.
docker compose --profile app stop
docker compose --profile app up -d --wait --wait-timeout 180

# Remove containers and network; retain the named database volume.
docker compose --profile app down
docker compose --profile app up -d --wait --wait-timeout 180
```

Patient records, accounts, affiliations, care history, and bed occupancy live in
the `postgres-data` volume. Container recreation retains them. Browser sessions
are in memory, so users sign in again after the app restarts. Keep the same Compose
project name and directory when reusing a volume; choosing a new project name
creates a separate database volume.

For an update, make a database backup, stop application writers, update the source,
then rebuild and start the app:

```powershell
docker compose stop app
docker compose --profile app up --build -d --wait --wait-timeout 180
```

Flyway applies new migrations and Hibernate validates the resulting schema.
Current index migrations use ordinary DDL, so plan downtime for upgrades. Schema
rollback is not automated; rebuilding an older app image does not undo migrations.
The images track Java 21 and PostgreSQL 17 patch updates rather than immutable
digests. `docker compose --profile app build --pull app` refreshes Java base images;
review and test image updates before using an existing database.

Initial passwords provision missing accounts only. After a successful first start,
account bootstrap passwords can be removed from `.env` while retaining usernames;
the existing passwords still work. Changing these environment values does not reset
an existing account's password. `DB_PASSWORD` remains necessary for every startup,
and changing it does not change the password already initialized inside PostgreSQL.
Do not remove a database volume to fix a credential mismatch.

## Backup and inspect

Write the dump inside the database container, then copy it out. This preserves the
binary archive on Windows PowerShell as well as PowerShell 7:

```powershell
New-Item -ItemType Directory -Force backups
docker compose exec -T postgres pg_dump -U clinicflow -d clinicflow -Fc -f /tmp/clinicflow.dump
docker compose cp postgres:/tmp/clinicflow.dump ./backups/clinicflow.dump
docker compose exec -T postgres pg_restore --list /tmp/clinicflow.dump
```

The `backups` folder is ignored by Git. Use a dated filename for each retained
backup. Listing the archive checks its contents; it is not a restore rehearsal.
Keep a separate copy outside the machine running the containers.

```powershell
docker compose --profile app logs --tail 100 app postgres
Invoke-RestMethod http://localhost:8080/actuator/health
```

A blank database password fails Compose validation. A new operator without an
initial password fails app startup; check the app log. Failed migration checks
preserve inconsistent existing data for investigation, rather than repairing or
deleting records. See [data integrity](data-integrity.md) for V7 diagnostics.

The app uses a non-root user, a read-only root filesystem, temporary storage at
`/tmp`, and a graceful shutdown allowance. For a publicly accessible demonstration,
configure HTTPS and set `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`; the checked-in
Compose file deliberately keeps HTTP access local. Backups, monitoring, account
administration, TLS/proxy configuration and any public hosting remain deployment
responsibilities, not claims that this is a production hospital installation.

## Deployment acceptance

GitHub Actions builds the image from source and starts a fresh Compose project.
It checks the runtime user and read-only filesystem, runs the existing inpatient
demo including cancellation workflows, and calls `scripts/deployment-smoke.py` to
create an active encounter with closed/current location and physician histories.
It then removes and recreates both containers **without removing the volume**,
with account bootstrap passwords omitted. Fresh logins using the original
passwords must still work; API snapshots, dictionaries, occupancy and migration
versions must match. Viewer writes and administrator clinical reads remain forbidden.

The `deployment-reports` CI artifact retains the fictional record snapshot,
migration versions and container logs. CI removes only its run-specific disposable
stack and volume after verification. No container registry or public site is used.

For a manual run on a disposable local stack, Python 3.10+ can execute:

```powershell
python scripts/deployment-smoke.py seed --state target/deployment/state.json
# Recreate the containers while retaining the volume, then verify:
python scripts/deployment-smoke.py verify --state target/deployment/state.json
```

Export the three accounts' original usernames/passwords in that shell; the Python
script does not load `.env`. Use `--base-url http://127.0.0.1:8081` for a different
app port. Only loopback HTTP URLs are accepted. The script writes fictional records
and leaves one active bed occupancy in place; run it on a disposable demonstration
database with the two active demo beds available. The `seed` mode refuses to
overwrite a snapshot; `verify` reads that exact saved state.

Startup ordering follows Docker's [health-based dependency](https://docs.docker.com/compose/how-tos/startup-order/)
and [Compose profile](https://docs.docker.com/compose/how-tos/profiles/) behavior.
