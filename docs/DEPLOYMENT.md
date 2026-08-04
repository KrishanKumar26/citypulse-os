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
| All nine compose services together | **Never done** — see §5 |
| CI container build | **Not built** — Phase 8 |
| Cloud deployment | **Not built** — Phase 8 |

Nothing here is a production deployment guide, because nothing has been deployed.
What follows is accurate about what was run.

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
