#!/usr/bin/env bash
# Keep the curated layer current, without Kafka or Spark.
#
#   set -a && . .env && set +a
#   bash data-engineering/scripts/live_loop.sh
#
# The generator has a real-time mode, but on the local path nothing consumes
# what it writes: `pipeline.local_runner` reads a file once and exits, and the
# streaming consumer lives on the Kafka side. Without this loop the dashboard
# goes stale the moment the last backfill ends.
#
# Each cycle reads the curated watermark, generates exactly the events between
# that watermark and the last completed window, and loads them.
#
# Stop it with Ctrl-C. Nothing is left half-written: a cycle either loads a
# whole window or does not run.
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
cd "$HERE"

INTERVAL=${INTERVAL:-60}
WINDOW_MINUTES=${WINDOW_MINUTES:-5}
# Must match the tick rate the history was generated at. A window's
# vehicle_count is a SUM over the readings inside it, so a denser rate here
# would inflate every live window against a baseline learned at the old rate,
# and the detector would report a spike that is an artefact of the generator
# rather than anything the city did.
TICK_SECONDS=${TICK_SECONDS:-300}
PY=${PYTHON:-.venv/bin/python}
DB=${CITYPULSE_PG_DATABASE:-citypulse}

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

if [[ -z "${CITYPULSE_DB_PASSWORD:-}" ]]; then
  echo "CITYPULSE_DB_PASSWORD is not set. Source the repository .env first." >&2
  exit 1
fi

echo "Live loop: every ${INTERVAL}s, ${WINDOW_MINUTES}-minute windows, ${TICK_SECONDS}s ticks. Ctrl-C to stop."

while true; do
  # Generate only as far as the last *completed* window. Running to wall-clock
  # would load a partial window, and since the watermark would then sit in the
  # future, the next cycle would have nothing valid left to ask for. Whole
  # windows only, so every one carries a full sample count.
  from=$(psql -d "$DB" -tAc \
    "SELECT to_char(max(window_end) AT TIME ZONE 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"') FROM zone_metrics;")
  to=$(psql -d "$DB" -tAc \
    "SELECT to_char(date_trunc('hour', now() AT TIME ZONE 'UTC')
                    + interval '${WINDOW_MINUTES} min'
                      * floor(extract(minute FROM now() AT TIME ZONE 'UTC') / ${WINDOW_MINUTES}),
                    'YYYY-MM-DD\"T\"HH24:MI:SS\"Z\"');")

  # No watermark means an empty database: backfill history first, or there is
  # no baseline for anything this loop produces to be measured against.
  if [[ -z "$from" || ! "$from" < "$to" ]]; then
    sleep "$INTERVAL"
    continue
  fi

  # The JSONL sink opens its file in append mode, standing in for a Kafka topic,
  # which is also append-only. Reusing one path across cycles would replay every
  # earlier cycle's events into this one's load.
  events="$WORK/tick.jsonl"
  rm -f "$events"

  "$PY" -m generator.main --sink jsonl --out "$events" --no-realtime \
      --simulate-from "$from" --simulate-to "$to" \
      --tick-seconds "$TICK_SECONDS" --quiet

  "$PY" -m pipeline.local_runner --input "$events" --quiet

  echo "[$(date -u +%H:%M:%SZ)] loaded $from -> $to"
  sleep "$INTERVAL"
done
