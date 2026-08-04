# CityPulse Data Platform

Synthetic city generator, ingestion pipeline and transformation layer
(PRD §19–25).

## Layout

```
common/       Event contracts, validation rules, derivations. Pure Python —
              no Spark, Kafka or database imports, so every rule is unit
              testable without a cluster.
generator/    Synthetic city model and CLI.
pipeline/     Validation → aggregation → PostgreSQL. `local_runner.py` runs
              the whole path in one process; the Spark job wraps the same
              modules for the distributed path.
dbt/          Staging, intermediate and mart models plus data quality tests.
tests/        pytest suite for common/, generator/ and pipeline/.
```

The reason `common/` holds no I/O is that the Spark job and the local runner
both import it. If the two ever disagree on the same input, the fault is in the
distribution around the logic, not the logic itself.

## Setup

```bash
python3.12 -m venv .venv          # not python3 — the default here is 3.14
.venv/bin/pip install -r requirements-dev.txt
```

### macOS certificate note

The python.org macOS build ships without a usable certificate trust store.
`pip` works because it vendors its own, but any package whose build step
fetches over HTTPS fails — `dbt-core` downloads a wheel during metadata
generation and dies with `CERTIFICATE_VERIFY_FAILED`.

Export the bundle `certifi` provides rather than modifying the system Python:

```bash
export SSL_CERT_FILE="$PWD/.venv/lib/python3.12/site-packages/certifi/cacert.pem"
export REQUESTS_CA_BUNDLE="$SSL_CERT_FILE"
```

Running `/Applications/Python 3.12/Install Certificates.command` fixes it
globally instead, if you would rather change the interpreter.

## Running

All commands need the repository `.env` sourced for database credentials —
nothing is defaulted (PRD §30):

```bash
set -a && . ../.env && set +a
```

### Generate events

```bash
# One minute of live events to a file
.venv/bin/python -m generator.main --sink jsonl --out ../data/events.jsonl --duration 60

# A week of history as fast as the machine allows
.venv/bin/python -m generator.main --sink jsonl --out backfill.jsonl --no-realtime \
    --seed 7 --simulate-from 2026-07-27T00:00:00Z --simulate-to 2026-08-03T00:00:00Z \
    --tick-seconds 300

# Stream to Kafka
.venv/bin/python -m generator.main --sink kafka --bootstrap-servers localhost:9092
```

`--seed` makes a run reproducible: same seed and same simulated clock replays
identical events, which is what makes the pipeline testable end to end.

### Load into PostgreSQL

```bash
.venv/bin/python -m pipeline.local_runner --input backfill.jsonl
```

Backfilling history needs the lateness watermark raised, or every record is
correctly rejected as too old:

```bash
.venv/bin/python -m pipeline.local_runner --input backfill.jsonl \
    --now 2026-08-03T00:00:00Z --max-lateness-hours 200
```

The default six-hour watermark exists to stop stragglers rewriting settled
windows on the streaming path. A deliberate historical load is a different
operation, so it has to say so.

### Run the Spark job without a cluster

The streaming job runs in Spark's local mode against a directory of JSON files,
so it can be exercised with no broker and no containers:

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
mkdir -p /tmp/spark-in && cp backfill.jsonl /tmp/spark-in/

.venv/bin/python -m pipeline.spark_job \
    --source file --input-path /tmp/spark-in \
    --master 'local[2]' --once \
    --write-lake --lake-scheme file --lake-bucket /tmp/citypulse-lake
```

This is not a toy path. It drives the real streaming query, the validation UDF,
`foreachBatch`, the PostgreSQL upserts, DLQ routing and partitioned Parquet
writes — everything except the Kafka and S3A hops, which are swapped in by
configuration rather than by different code.

Against Kafka the invocation is the same with `--source kafka`:

```bash
.venv/bin/python -m pipeline.spark_job --source kafka --bootstrap-servers localhost:29092
```

The two paths are expected to produce identical output. Confirm it after a
change by running the same input through both and diffing `zone_metrics`; a
difference means the fault is in the Spark wiring, because the logic between
them is the same import.

### Transform

```bash
export DBT_PROFILES_DIR="$PWD/dbt"
cd dbt && ../.venv/bin/dbt build      # models + tests
```

`dbt build` fails the run on any test failure, so a broken model cannot be
published silently.

### Test

```bash
.venv/bin/python -m pytest            # logic tests, no database needed
```

## Data quality

Invalid records are never dropped. They go to `ingestion_dlq` with one of the
reason codes in `common/validation.py`, together with the payload that caused
the rejection — a malformed feed is itself a signal, and diagnosing it needs
the evidence. `assert_*` tests under `dbt/tests/` then assert the invariants
that survived: that a congestion label matches the occupancy it was derived
from, that an AQI band matches its number, that no future-dated event was
stored, and that synthetic data stayed labelled all the way into the marts
(PRD §42).
