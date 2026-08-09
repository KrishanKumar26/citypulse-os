# CityPulse OS — Data Pipeline

How city telemetry becomes curated conditions, and what happens to the records
that do not make it.

Related: [ARCHITECTURE.md](ARCHITECTURE.md) for where this sits, [ML.md](ML.md)
for what is built on top of it.

---

## 1. Shape

```
generator ──► Kafka ──► Spark Structured Streaming ──┬──► PostgreSQL  curated windows
                        (validate, window, derive)   ├──► PostgreSQL  raw events
                                                     ├──► PostgreSQL  ingestion_dlq
                                                     └──► MinIO       raw Parquet
```

The same path runs without Kafka or Spark:

```
generator ──► JSONL ──► pipeline.local_runner ──► PostgreSQL
```

**Both drive the same code.** Validation, derivation and windowing live in
`data-engineering/common/` as plain Python with no Spark or Kafka imports. The
Spark job and the local runner both import those modules, so they cannot
disagree about what a valid record is or what a window contains.

That is not a claim: the same input was pushed through both paths and
`zone_metrics` was diffed column by column. **200 rows, identical.**

---

## 2. The generator

`data-engineering/generator/` produces a synthetic Indian metro. Not random
noise — the signals are coupled the way a real city's are:

- **Bimodal commute peaks** at roughly 08:30 and 17:30 local, damped at weekends
- **Zone character** — a transit hub and a residential street behave differently
  at the same hour
- **Weather affects capacity** — wet roads lengthen following distances, so the
  same tarmac carries fewer vehicles
- **Incidents reduce capacity** for their duration
- **Events raise demand** in their zone and, less, in neighbouring ones
- **Air quality tracks traffic and rain** — congestion raises it, rain washes it
  out

Every event carries `demo_data: true` from the moment it is created. The label
travels with the data all the way to the browser rather than being applied at
the edge.

```bash
# a week of history, deterministic
python -m generator.main --sink jsonl --out /tmp/week.jsonl --no-realtime \
    --simulate-from 2026-07-01T00:00:00Z --simulate-to 2026-07-08T00:00:00Z \
    --tick-seconds 300 --seed 7

# live, to Kafka
python -m generator.main --sink kafka --bootstrap-servers localhost:29092 --tick-seconds 10
```

Seeded, so the same seed reproduces the same city exactly. That matters for
debugging: a defect found in generated data can be reproduced rather than waited
for.

### Two defects the coupling produced

Both were caught by running, not by reading:

**Speed contradicted its own label.** The BPR curve used the textbook highway
coefficients (α=0.15, β=4), which degrade travel time only ~15% at capacity. A
zone at 83% occupancy reported ~45 km/h while labelled `HIGH` — a dashboard tile
would have read "HIGH congestion" beside a near-free-flow speed. Replaced with
signalised-urban coefficients (α=0.85, β=5.0).

**Incidents could erase a zone.** Capacity factors multiplied without a floor on
the product, so three concurrent incidents left 4% of rated capacity (0.35³) and
occupancy reached 26×. The validator rejected those records, which is how it was
found. The combined factor is now floored — even a badly blocked arterial keeps
moving something.

---

## 3. Validation

Every record is validated before it can reach a curated window. There are twelve
rejection reasons, and each produces a row in `ingestion_dlq` with the reason
code, a detail string and the raw payload.

| Reason | Fires when |
|---|---|
| `MALFORMED_JSON` | The payload will not parse |
| `SCHEMA_MISMATCH` | Parsed, but not a JSON object |
| `MISSING_REQUIRED_FIELD` | A required field is absent |
| `UNSUPPORTED_EVENT_TYPE` | An event type the platform does not handle |
| `UNKNOWN_ZONE` · `UNKNOWN_CITY` · `UNKNOWN_SOURCE` | References something not in the catalogue |
| `TIMESTAMP_INVALID` | Not ISO-8601 with an offset |
| `TIMESTAMP_IN_FUTURE` | Beyond a small clock-skew allowance |
| `TIMESTAMP_TOO_OLD` | Outside the streaming watermark |
| `VALUE_OUT_OF_RANGE` | A field outside its physical bounds |
| `INVALID_ENUM_VALUE` | An enum outside its declared set |

### Why the payload is kept

A rejection with only a reason code is nearly useless at 3 a.m. The raw payload
is stored, truncated to 8 KB — a 10 MB malformed blob should not become a 10 MB
row, and the first 8 KB is almost always enough to see what went wrong.

### The DLQ writer must never be the thing that fails

`ingestion_dlq.topic` is `VARCHAR(120)`. Early on, `write_dlq` clamped
`reason_detail` and `raw_payload` but not `topic`, so a long source identifier
raised `StringDataRightTruncation` and **aborted the entire micro-batch —
including the valid records in it** — at exactly the moment bad input was already
being handled.

That failure mode is worse than it sounds: the error path failing turns "a few
bad records were quarantined" into "the whole batch failed", precisely when the
pipeline is under stress. Every field is now clamped, with a test asserting the
constants still match the migration.

### Verified

16 deliberately corrupt records through the pipeline: **12 distinct reason codes
fired, and zero bad rows reached the curated layer.**

---

## 4. Windowing and derivation

Events are aggregated into **five-minute windows per zone**. Within a window:

- Traffic readings are averaged; vehicle counts summed
- Weather is a city-level reading, copied onto each of its zones
- **Incidents are reconciled by `external_id`**, not counted per event

That last one was a real defect. An incident is reported repeatedly as it moves
from `REPORTED` to `CLEARED`. Treated as independent records, the first reads as
permanently open — a week of data reported up to **52,544 concurrent incidents
per zone against 733 actual**, which inflated every composite risk score.
Average risk fell from 62–65 to a realistic 30–32 once reconciled.

Derived per window, in `common/transforms.py`:

| Field | From |
|---|---|
| `congestion_level` | Occupancy bands: ≤0.55 NORMAL, ≤0.80 MODERATE, ≤1.00 HIGH, above CRITICAL |
| `average_speed_kph` | BPR curve over occupancy |
| `aqi_category` | CPCB bands |
| `aqi_source` | `MEASURED`, `MODELLED` or `SYNTHETIC` — see below |
| `risk_score` | Weighted composite: congestion 0.40, incidents 0.25, air 0.20, weather 0.15 |
| `risk_level` | The same four-state scale as congestion |

**Missing components are excluded and the remaining weights renormalised**, so a
zone with traffic but no air-quality feed scores on what it has rather than being
penalised for the gap. A zone with nothing measured returns `null` — it is
unknown, not low risk.

### Real air arrives after the window it belongs to

Generated feeds travel event → window → `zone_metrics` in one pass, because the
aggregator sees them in the batch it is given. The two real air feeds do not:
`ingest/waqi.py` and `ingest/open_meteo.py` run on their own schedule and write
straight to `air_quality_events`, so the curated windows covering those readings
were already built, from generated air, before the real ones arrived.

`pipeline/air_provenance.py` closes that gap. For each curated window it finds
the best provenance covering it, replaces the AQI and its band outright, records
which of the three it is, and recomputes `risk_score` — risk is derived from AQI
among other things, so changing one without the other would leave a window whose
displayed air and displayed risk disagree about what the air was.

Three properties are load-bearing:

- **Ranked, never averaged.** `MEASURED` beats `MODELLED` beats `SYNTHETIC`, and
  only the winning tier contributes to the mean. Two stations covering the same
  zone are averaged with each other; a station and a model never are.
- **Ranked by kind, not recency.** A station reading from the top of the hour
  beats a CAMS value from thirty minutes ago. Provenance answers *what sort of
  thing produced this*, and a fresher model output is still a model output.
- **Carried forward, then expired.** Both feeds publish hourly against
  five-minute windows, so a reading covers the windows after it for one hour and
  then stops. Carrying it indefinitely would report a feed that went quiet last
  night as though the instrument were still speaking.

The overlay is idempotent, so the hourly refresh — which regenerates the last
three hours and would otherwise bury the real readings under invented ones —
simply re-applies it after every load.

### A rounding defect worth recording

`occupancy_ratio` is stored as `NUMERIC(6,4)`, and the congestion label was
derived from the full-precision reading *before* rounding. A value of 0.550004
was labelled `MODERATE` and then stored as `0.5500`, which reads as `NORMAL`.
Only band boundaries were affected — 5 rows in 40,323 — and it was found by a
dbt test on real data after 116 unit tests had passed. Both the generator and
the loader now derive the label from the already-rounded value.

---

## 5. Storage

### PostgreSQL

| Table | Holds |
|---|---|
| `traffic_events` `weather_events` `air_quality_events` `incidents` `city_events` | Raw, as received |
| `zone_metrics` | Curated five-minute windows — what the API reads |
| `ingestion_dlq` | Rejected records with their reason |
| `data_quality_metrics` | Per-stage counts, for the freshness and validity figures |

Upserts are keyed on `(zone_id, window_start)`, so replaying a window overwrites
rather than duplicating. That is what makes a reprocess safe.

### Object storage

MinIO, S3-compatible, laid out by layer:

```
raw/        exactly what the producer sent, never rewritten
processed/  validated and typed
curated/    windowed metrics
features/   model inputs
rejected/   what validation refused
```

Partitioned Hive-style by `dt=YYYY-MM-DD/hour=HH`. **Zone is deliberately not a
partition key** — 20 zones × 24 hours × 5 event types would produce thousands of
tiny files a day, which is the classic way to make an object-store lake slow.

Raw is written *before* validation filters anything, including the records
validation goes on to reject. That is what makes the pipeline reproducible: a
bug in transformation costs a reprocess rather than the data.

---

## 6. dbt

`data-engineering/dbt/` builds the analytics layer:

```
staging       →  intermediate    →  marts
typed, clean     joined, enriched    dim_zones, fct_zone_conditions_hourly,
                                     agg_pipeline_quality_daily
```

`dbt build` runs models and their tests interleaved, so a model producing bad
data never reaches the marts the API reads — the run fails first.

**11 models, 97 tests, all passing.**

---

## 7. Running it

### Without Kafka or Spark

The fastest path, and the one used for most development:

```bash
cd data-engineering
set -a && . ../.env && set +a

python -m generator.main --sink jsonl --out /tmp/events.jsonl --no-realtime \
    --simulate-from 2026-07-01T00:00:00Z --simulate-to 2026-07-08T00:00:00Z
python -m pipeline.local_runner --input /tmp/events.jsonl
```

### With Spark, no broker

Exercises the real streaming query, the validation UDF, `foreachBatch`, the
upserts, DLQ routing and partitioned Parquet — everything except the Kafka hop:

```bash
export JAVA_HOME=$(/usr/libexec/java_home)
mkdir -p /tmp/spark-in && cp /tmp/events.jsonl /tmp/spark-in/

python -m pipeline.spark_job --source file --input-path /tmp/spark-in \
    --master 'local[2]' --once --write-lake --lake-scheme file --lake-bucket /tmp/lake
```

### Full streaming

```bash
docker compose --profile streaming up -d kafka minio
python -m kafka_admin.create_topics --bootstrap-servers localhost:29092
docker compose --profile streaming run --rm minio-init

python -m generator.main --sink kafka --bootstrap-servers localhost:29092 &
python -m pipeline.spark_job --source kafka --bootstrap-servers localhost:29092
```

---

## 8. Kafka topics

Six, named `citypulse.<domain>.v<schema-version>`. The version is in the *topic
name* rather than only the payload, so an incompatible schema change becomes a
new topic existing consumers keep reading past — rather than one that breaks
them at the next deploy.

| Topic | Partitions | Retention |
|---|---|---|
| `citypulse.traffic.v1` | 6 | 7 days |
| `citypulse.weather.v1` | 3 | 7 days |
| `citypulse.air-quality.v1` | 3 | 7 days |
| `citypulse.incidents.v1` | 3 | 30 days |
| `citypulse.city-events.v1` | 3 | 30 days |
| `citypulse.dlq.v1` | 3 | 30 days |

**Keyed by zone** (weather by city), so one place's events land on one partition
and stay in order — windowed aggregation depends on that. Verified: 155 messages
spread across all six partitions.

The DLQ topic outlives the source topics deliberately. A rejection may go
unnoticed for days, and the evidence is useless once it has expired.

Auto-creation is disabled. It would silently produce a one-partition topic on a
typo'd name, which then looks like a consumer that mysteriously receives nothing.

### Two defects found the first time a real broker was involved

**The declared image did not exist.** `bitnami/kafka:3.9` no longer resolves —
Bitnami moved its public catalogue in 2025. Switched to `apache/kafka:4.0.0`,
whose environment variables are also named differently.

**A local package shadowed the client library.** The topic manager lived in
`data-engineering/kafka/`, so `from kafka import KafkaProducer` resolved to the
project's own package rather than the driver. This would have broken the
generator's entire Kafka sink, not just the topic tool. Renamed to
`kafka_admin/`.

---

## 9. Data quality

`data_quality_metrics` records per stage and window: records received, valid,
rejected, duplicate, late, validity ratio, and maximum ingestion lag. Exposed
through `agg_pipeline_quality_daily`, so "how healthy was ingestion yesterday" is
a query rather than a log search.

The historical-ingestion DAG fails a backfill whose validity ratio falls below a
configured floor. A run that quietly loaded 40% of its data is worse than one
that failed: the gap is invisible once the run is green, and every chart built on
it is silently wrong.
