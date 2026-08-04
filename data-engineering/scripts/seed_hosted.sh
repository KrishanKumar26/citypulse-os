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

# Loaded one day at a time, not in one pass.
#
# A single load of four weeks is roughly 200,000 events over one connection.
# Against a local database that is fine; against a hosted one across a network
# it is not — the first attempt here died with "SSL SYSCALL error: EOF detected"
# partway through, and because the loader commits at the end, the whole thing
# rolled back and nothing landed.
#
# A day per iteration keeps each transaction small enough to survive the trip,
# and a failure costs one day rather than the entire seed.
echo "==> Generating and loading $DAYS days, one day at a time"
loaded=0
for offset in $(seq "$DAYS" -1 1); do
  DAY_FROM=$(date -u -d "$offset days ago" +%Y-%m-%dT00:00:00Z 2>/dev/null \
           || date -u -v-"${offset}"d +%Y-%m-%dT00:00:00Z)
  NEXT=$((offset - 1))
  if [ "$NEXT" -eq 0 ]; then
    DAY_TO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  else
    DAY_TO=$(date -u -d "$NEXT days ago" +%Y-%m-%dT00:00:00Z 2>/dev/null \
           || date -u -v-"${NEXT}"d +%Y-%m-%dT00:00:00Z)
  fi

  $PY -m generator.main --sink jsonl --out "$WORK/day.jsonl" --no-realtime \
      --seed $((SEED + offset)) --tick-seconds 300 \
      --simulate-from "$DAY_FROM" --simulate-to "$DAY_TO" --quiet

  # Retried once: a dropped connection to a hosted database is a transient
  # condition, and losing a whole seed to one is not worth it.
  if ! $PY -m pipeline.local_runner --input "$WORK/day.jsonl" \
        --max-lateness-hours $((DAYS * 24 + 24)) --quiet 2>/dev/null; then
    echo "    ${DAY_FROM%T*} failed, retrying once"
    if ! $PY -m pipeline.local_runner --input "$WORK/day.jsonl" \
          --max-lateness-hours $((DAYS * 24 + 24)) --quiet; then
      echo "    ${DAY_FROM%T*} failed again — continuing without it"
      continue
    fi
  fi
  loaded=$((loaded + 1))
  printf "    %s  (%d/%d)\n" "${DAY_FROM%T*}" "$loaded" "$DAYS"
done

if [ "$loaded" -eq 0 ]; then
  echo "No days loaded. Nothing downstream can be built from an empty database."
  exit 1
fi

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
