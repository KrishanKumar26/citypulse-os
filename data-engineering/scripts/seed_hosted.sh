#!/usr/bin/env bash
# Load a hosted database with everything the dashboard needs to be worth looking at.
#
#   CITYPULSE_PG_DSN='postgresql://user:pass@host/db?sslmode=require' \
#     bash data-engineering/scripts/seed_hosted.sh
#
# Order matters and is not arbitrary: each step reads what the previous one
# wrote. Forecasts need history to train on; anomalies need baselines; City
# Memory needs outcomes that have already happened.
#
# Run this *after* the backend has booted once against the same database, so
# Flyway has created the schema. This script only fills it.
#
# Free-tier note: four weeks of five-minute windows is roughly 150 MB, which
# fits Neon's 500 MB free tier with room to spare. Two weeks halves it if a
# tighter tier is in use — pass DAYS=14.
set -euo pipefail

DAYS=${DAYS:-28}
SEED=${SEED:-101}
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PY=${PYTHON:-python3}
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if [ -z "${CITYPULSE_PG_DSN:-}" ]; then
  echo "CITYPULSE_PG_DSN is required, e.g."
  echo "  export CITYPULSE_PG_DSN='postgresql://user:pass@host/db?sslmode=require'"
  exit 1
fi

cd "$HERE"

echo "==> Checking the schema exists"
# A clear message beats a stack trace forty seconds into a generation run.
$PY - <<'PY'
import os, sys
import psycopg
with psycopg.connect(os.environ["CITYPULSE_PG_DSN"]) as c, c.cursor() as cur:
    cur.execute("SELECT to_regclass('public.zone_metrics') IS NOT NULL")
    if not cur.fetchone()[0]:
        sys.exit("zone_metrics does not exist. Start the backend against this "
                 "database once so Flyway can migrate, then re-run.")
    cur.execute("SELECT count(*) FROM zones")
    zones = cur.fetchone()[0]
    if zones == 0:
        sys.exit("No zones seeded. Migration V3 should have created them.")
    print(f"    schema present, {zones} zones")
PY

FROM=$(date -u -d "$DAYS days ago" +%Y-%m-%dT00:00:00Z 2>/dev/null \
     || date -u -v-"${DAYS}"d +%Y-%m-%dT00:00:00Z)
TO=$(date -u +%Y-%m-%dT%H:%M:%SZ)

echo "==> Generating $DAYS days of telemetry ($FROM to $TO)"
$PY -m generator.main --sink jsonl --out "$WORK/events.jsonl" --no-realtime \
    --seed "$SEED" --tick-seconds 300 \
    --simulate-from "$FROM" --simulate-to "$TO" --quiet

echo "==> Loading through validation and windowing"
# max-lateness is raised because this is a deliberate historical backfill; the
# streaming watermark exists to reject genuinely stale live data, not this.
$PY -m pipeline.local_runner --input "$WORK/events.jsonl" \
    --max-lateness-hours $((DAYS * 24 + 24)) --quiet

echo "==> Training the forecast model"
$PY -m ml.train --model-version v1 2>&1 | tail -3

echo "==> Issuing forecasts"
$PY -m ml.predict 2>&1 | tail -1

echo "==> Scoring past forecasts against what happened"
# Backfilled issue points, so accuracy has something to report from the start.
# Without this the accuracy panel is empty on a fresh deployment and looks
# broken rather than new.
$PY -m ml.predict --backfill-hours 24 --backfill-step-minutes 60 2>&1 | tail -1
$PY -m ml.score 2>&1 | head -3

echo "==> Learning baselines, detecting anomalies, building memory"
$PY -m intelligence.jobs all

echo "==> Building the analytics marts"
(cd dbt && DBT_PROFILES_DIR=. dbt build 2>&1 | tail -2)

echo
echo "==> Loaded"
$PY - <<'PY'
import os
import psycopg
from psycopg.rows import dict_row
with psycopg.connect(os.environ["CITYPULSE_PG_DSN"], row_factory=dict_row) as c, c.cursor() as cur:
    cur.execute("""
        SELECT (SELECT count(*) FROM zone_metrics)      AS windows,
               (SELECT count(*) FROM forecasts)         AS forecasts,
               (SELECT count(*) FROM forecast_accuracy) AS scored,
               (SELECT count(*) FROM anomalies)         AS anomalies,
               (SELECT count(*) FROM situation_memory)  AS situations,
               (SELECT count(*) FROM condition_correlations) AS correlations,
               (SELECT count(*) FROM ingestion_dlq)     AS rejected,
               pg_size_pretty(pg_database_size(current_database())) AS size
    """)
    r = cur.fetchone()
    for k, v in r.items():
        print(f"    {k:<13} {v}")
PY
