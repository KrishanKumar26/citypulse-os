# CityPulse OS — Deployment

Running the platform locally, in containers, and what has and has not been
proven to work.

---

## 1. Honest status

Read this before anything else.

| | Status |
|---|---|
| Local development (native processes) | **Verified** — this is how the platform was built and tested |
| Kafka, MinIO, Spark individually in containers | **Verified** — each started and driven end to end |
| Airflow image and DAG loading | **Verified** — image builds, both DAGs parse with no import errors |
| CI: build, test and scan on every push | **Verified** — six jobs, green |
| CI container build and boot check | **Verified** — images build and the backend reports healthy |
| All nine compose services together | **Attempted in CI** — see §5 |
| Hosted database (Neon), migrated and seeded | **Verified** — schema v10, 165,800 windows, 200 MB |
| Backend and frontend on Render + Vercel | **Written, not yet run** — needs account access |
| AWS deployment | **Not built** — needs an account and a spend decision |

Nothing here is a production deployment guide. What follows is accurate about
what was run, and explicit about what was only written.

---

## 2. Local development

The path with the fewest moving parts, and the one everything was verified on.

### Prerequisites

- Java 21 · Node 20+ · Python 3.12 · PostgreSQL 17
- ~2 GB disk for dependencies

### Configure

```bash
cp .env.example .env
```

Three values are required and have no defaults — the application refuses to start
without them rather than falling back to something guessable:

```
CITYPULSE_DB_PASSWORD      openssl rand -base64 24
CITYPULSE_JWT_SECRET       openssl rand -base64 48
POSTGRES_PASSWORD          must match CITYPULSE_DB_PASSWORD
```

### Databases

```bash
createdb citypulse
createdb citypulse_test
```

The test database is separate and real. The schema relies on partial unique
indexes, check constraints and `TIMESTAMPTZ` semantics that an in-memory
substitute does not reproduce, so a test passing against H2 would prove nothing
about production behaviour.

### Run

```bash
# backend — Flyway migrates on boot
cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run

# frontend
cd frontend && npm install && npm run dev
```

<http://localhost:3000> · API docs at <http://localhost:8080/swagger-ui.html>

### Populate

See [DATA_PIPELINE.md](DATA_PIPELINE.md) §7, or the quick path in the root
[README](../README.md).

---

## 3. Compose profiles

The stack is split into profiles rather than started as one unit, because it does
not fit comfortably in 8 GB. Airflow alone wants ~2 GB and Spark another ~1.5 GB;
starting everything on a laptop means swapping, which presents as a broken
pipeline rather than an exhausted machine.

| Profile | Services | Memory |
|---|---|---|
| *(none)* | postgres, backend, frontend | ~1.3 GB |
| `streaming` | + kafka, kafka-init, minio, minio-init, spark, generator | ~3.0 GB |
| `orchestration` | + airflow | ~2.5 GB |

```bash
docker compose up --build                       # core
docker compose --profile streaming up -d        # + pipeline
docker compose --profile orchestration up -d    # + DAGs
```

Every service declares an explicit memory limit, so a runaway container is killed
rather than taking the host down with it.

### Additional secrets

The `streaming` and `orchestration` profiles need more values in `.env`. Compose
fails to start without them rather than booting on vendor defaults:

```
MINIO_ROOT_PASSWORD      openssl rand -base64 24
AIRFLOW_FERNET_KEY       python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
AIRFLOW_SECRET_KEY       openssl rand -base64 32
AIRFLOW_ADMIN_PASSWORD   openssl rand -base64 18
```

### If PostgreSQL already runs on 5432

Common on a development machine. The compose database's host port is
configurable so the stack does not require stopping yours:

```
POSTGRES_HOST_PORT=5433
```

Containers still reach each other on 5432 regardless.

---

## 4. Ports

| Port | Service |
|---|---|
| 3000 | Frontend |
| 8080 | Backend API |
| 8081 | Airflow UI |
| 5432 | PostgreSQL (configurable) |
| 9000 / 9001 | MinIO API / console |
| 29092 | Kafka, external listener |

All bound to `127.0.0.1`. Nothing is reachable from the network without a
deliberate change.

---

## 5. What has not been done, and why

### The full stack has never run at once

Nine services need roughly 5 GB of images plus build layers. The development
machine ran out of disk mid-build — twice — and the container runtime's own VM
disk is 6.6 GB before any image is pulled.

Each service *has* been started and verified individually:

- Kafka in KRaft mode, healthy, six topics created with correct partition counts
- MinIO with the full lake layout
- Spark consuming from Kafka: 155 messages published, 155 consumed, 20 curated
  windows, matching raw-table counts
- Spark writing Parquet to MinIO over S3A
- Airflow image built; both DAGs load with no import errors

What is unproven is all of them healthy *simultaneously*. Nothing in the compose
definition is known to be wrong, but "it should work" is not "it ran", and this
is recorded rather than ticked.

**To do it**, free ~6 GB and bring the profiles up one at a time rather than
together — which is also what CI will do.

### Two environment failures worth knowing about

Both cost real time and neither was a code problem:

**Disk exhaustion is silent until it is total.** When the disk filled, the shell
tool itself could not create its output file, so commands failed before running.
Build caches and container images are the usual culprits.

**Two PostgreSQL installations will fight over 5432.** Homebrew's
`postgresql@18` launch agent started and took the port from Postgres.app, which
held the project's database. Every integration test failed with 10-second
connection timeouts and `psql` reported the `citypulse` role as non-existent. No
data was lost, but it presents as a mass test failure rather than a configuration
problem. `brew services stop postgresql@18` and restarting Postgres.app resolved
it.

---

## 6. Airflow

Two DAGs, both verified to load:

| DAG | Schedule | Does |
|---|---|---|
| `citypulse_historical_ingestion` | manual | Extract → validate → transform → load → quality check |
| `citypulse_daily_analytics` | 02:00 UTC | Source freshness → `dbt build` → publish freshness metrics |

Backfill is deliberately unscheduled — it is an operator decision for a specific
date range, and a schedule would silently re-load history.

There is **no model-training DAG**. Forecasting exists, but a DAG whose tasks
would be placeholders is the kind of fake production functionality the PRD rules
out. Training is run explicitly (`python -m ml.train`).

Airflow keeps its metadata in a separate database on the same server. Sharing the
application schema would let Airflow's migrations and Flyway's collide.

---

## 7. Health and observability

| Endpoint | |
|---|---|
| `GET /actuator/health` | Liveness; public |
| `GET /actuator/health/readiness` | Includes database connectivity |
| `GET /actuator/prometheus` | Metrics; requires `system:manage` |

Logs are structured, and every line carries the `requestId` returned to the
client — so a user reporting a failure can be matched to the exact log entry.

Data freshness is a query, not a log search: `agg_pipeline_quality_daily` reports
ingestion health per day, and `/api/v1/live/by-slug/{slug}` returns `asOf`,
`dataAgeSeconds` and `stale` on every snapshot.

---

## 8. Before this is deployed anywhere

Not done, and not to be skipped:

1. **Rotate every secret.** Development `.env` values must not travel.
2. **`CITYPULSE_DEMO_MODE=false`** only once real data is flowing — never while
   still serving generated telemetry.
3. **TLS**, with the API behind it.
4. **Managed PostgreSQL** with point-in-time recovery. The current setup has one
   database and one `pg_dump`.
5. **A real mail provider.** Email verification is currently disabled and
   verification links are written to the log.
6. **Container image scanning** in CI.
7. **Backups** and a tested restore. An untested restore is not a backup.

---

## 9. Free-tier deployment

A working public deployment at no cost, with limitations stated rather than
discovered.

| Component | Service | Free allowance |
|---|---|---|
| Database | Neon PostgreSQL | 500 MB — the seeded database is ~150 MB |
| Backend | Render web service | 512 MB RAM, stopped when idle |
| Frontend | Vercel | Generous for a project this size |
| Data refresh | GitHub Actions | 2,000 minutes/month on a private repo |

None requires a card.

### What this costs you in behaviour

**The backend stops after 15 minutes without traffic.** The next request waits
for it to start — roughly a minute for a JVM. Anyone opening the dashboard cold
sees a long pause and may conclude it is broken.

There is deliberately no keep-warm workflow. Pinging every ten minutes would
consume more than the entire free Actions allowance on nothing but pings. If
warmth matters more than the allowance, an external uptime monitor (UptimeRobot
and similar have free tiers) does the same job without spending it.

**There is no live pipeline.** Kafka and Spark have no free tier worth the name.
`refresh-demo-data.yml` runs the generator and the local pipeline runner every
six hours instead — the same validation, windowing and load path, driven on a
schedule rather than by a stream. Between runs the data ages, and the dashboard
says so: a window older than two hours is marked stale, which is correct
behaviour rather than a bug to hide.

**Neon's compute suspends when idle** and resumes in a few hundred milliseconds.
Noticeable on the first query, not after.

### Steps

**1. Database.** Create a Neon project. It gives you two endpoints, and the
difference matters:

```
pooled    ep-xxxx-pooler.region.aws.neon.tech    for the application
direct    ep-xxxx.region.aws.neon.tech           for migrations
```

**Migrations must use the direct endpoint.** The pooled one runs PgBouncer in
transaction mode, which cuts off a long DDL transaction partway through — the
first attempt here died with `SQL State 08006, connection reset` while V10 was
committing. Flyway rolled the whole migration back cleanly, so nothing was left
half-applied, but the schema simply never arrived. The direct endpoint applied
the same migration without complaint.

**Use the direct endpoint for the backend too**, not the pooled one, even though
a pooler is exactly right for the short transactions the application issues. The
backend runs Flyway on boot, so every deploy that carries a new migration would
attempt that DDL through PgBouncer and hit the same reset — turning a routine
deploy into a failed one at the worst possible moment.

Nothing is lost by it here. The free tier runs one instance, and HikariCP
already pools connections inside it; PgBouncer earns its place when many
instances share a database, which is not this. Revisit it if the backend is ever
scaled out, and move migrations to a separate step before doing so.

**2. Backend.** On Render, create a Blueprint from this repository — it reads
`render.yaml`. Set the variables marked `sync: false`:

```
CITYPULSE_DB_URL       jdbc:postgresql://<neon-host>/<db>?sslmode=require
CITYPULSE_DB_USER      <neon user>
CITYPULSE_DB_PASSWORD  <neon password>
```

Leave `CITYPULSE_FRONTEND_URL` and `CITYPULSE_CORS_ORIGINS` until step 3. Set
`CITYPULSE_BOOTSTRAP_ADMIN_EMAIL` and `..._PASSWORD` to have an administrator
created on first boot; leave them unset and the deployment has no accounts at
all, which is the safer default if the URL is public.

Note the JDBC form for the backend and the `postgresql://` form for the Python
tooling. Same database, two dialects.

Flyway migrates on boot. Watch the log until it reports the schema at the latest
version.

**3. Frontend.** Import the repository on Vercel, root directory `frontend`. Set
`NEXT_PUBLIC_API_BASE_URL` to the Render URL. It is inlined at build time, so
changing it later needs a rebuild, not just a restart.

Then go back to Render and set `CITYPULSE_FRONTEND_URL` and
`CITYPULSE_CORS_ORIGINS` to the Vercel URL. Without this the browser blocks
every request and the dashboard is silently empty.

**4. Data.** Everything renders empty until telemetry exists:

```bash
export CITYPULSE_PG_DSN='postgresql://user:pass@host/db?sslmode=require'
bash data-engineering/scripts/seed_hosted.sh
```

Four weeks of history, then forecasts, accuracy scoring, baselines, anomalies,
City Memory and the dbt marts — in that order, because each reads what the last
one wrote. Roughly forty minutes against Neon, most of it the day-by-day load.

What this produced when it was actually run:

| | |
|---|---|
| Curated windows | 165,800 over 28 days |
| Forecasts | 10,400, of which 10,000 scored against actuals |
| Models beating persistence | 20 of 20 |
| Baselines | 13,440 zone/metric/hour-of-week buckets |
| Anomalies | 215 |
| Situations in City Memory | 13,780 |
| Correlations | 30 pairs |
| dbt models | 108, all passing |
| Rejected records | 0 |
| Database size | 200 MB of Neon's 500 MB |

#### What went wrong doing it

**Bulk writes died repeatedly on `SSL SYSCALL error: EOF detected`.** Not a row
limit — a single send long enough to cross a network idle timeout gets cut, and
because the write commits at the end, the whole thing rolls back. It cost a
full seed twice before the cause was clear. Every bulk write now splits at
1,000 rows (`common/db.py`), and the seed loads one day per iteration with a
retry, so a dropped connection costs a day rather than everything.

**The most recent day failed twice and was skipped.** Detection found zero
anomalies as a direct result: it judges recent windows, and the recent windows
were the ones missing. Zero anomalies on a seeded database is a symptom, not a
clean bill of health — check `max(window_start)` in `zone_metrics` before
believing it.

**`dbt` was not on PATH.** The marts step failed at the very end of an
otherwise successful forty-minute run. The script now resolves `dbt` from the
interpreter's own directory.

**5. Keep it current.** Add `HOSTED_PG_DSN` as a repository secret with the same
connection string. The refresh workflow skips cleanly until it is set, so
nothing fails in the meantime.

### Before sharing the URL

- `CITYPULSE_DEMO_MODE` stays `true`. The data is generated, and PRD §42 forbids
  presenting it as real.
- Use a real password for the bootstrap administrator. The deployment is
  reachable by anyone with the link.
- Neon's free tier has no point-in-time recovery. `pg_dump` before anything
  destructive.
