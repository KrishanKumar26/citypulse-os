"""Ingestion pipeline without a cluster.

  python -m pipeline.local_runner --input events.jsonl

Reads the same newline-delimited JSON that Kafka would deliver, applies the
same validation and aggregation modules the Spark job uses, and writes to the
same tables. What it does *not* do is scale — it is single-process and reads a
file rather than subscribing to a topic.

It exists for two reasons. It makes the pipeline runnable and verifiable on a
machine with no Docker, and it isolates whether a bug is in the processing
logic or in the distribution around it: if the local runner and the Spark job
disagree on the same input, the fault is in the Spark wiring, because the logic
between them is literally the same import.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common.validation import (  # noqa: E402
    MAX_EVENT_LATENESS,
    Rejected,
    ReferenceData,
    Valid,
    validate,
)
from generator import catalog as catalog_module  # noqa: E402
from pipeline import loader, provenance  # noqa: E402
from pipeline.aggregate import DEFAULT_WINDOW, aggregate  # noqa: E402


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="local_runner",
        description="Validate, aggregate and load a JSONL event file into PostgreSQL.",
    )
    parser.add_argument(
        "--input", type=Path, required=True,
        help="Newline-delimited JSON events, as produced by the generator's jsonl sink.",
    )
    parser.add_argument(
        "--window-minutes", type=int, default=int(DEFAULT_WINDOW.total_seconds() // 60),
        help="Tumbling window size for curated zone metrics.",
    )
    parser.add_argument(
        "--batch-size", type=int, default=5000,
        help="Records validated and written per transaction.",
    )
    parser.add_argument(
        "--now", type=str,
        help="Override the clock used for lateness checks, e.g. 2026-08-03T00:00:00Z. "
             "Backfilling historical data needs this, or every record is rejected as too old.",
    )
    parser.add_argument(
        "--max-lateness-hours", type=float,
        help="Raise the lateness watermark for a deliberate historical backfill. "
             "Streaming keeps the default so stragglers cannot rewrite settled windows.",
    )
    parser.add_argument("--skip-raw", action="store_true", help="Aggregate only; do not write raw events.")
    parser.add_argument("--quiet", action="store_true")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if not args.input.exists():
        raise SystemExit(f"input file not found: {args.input}")

    now = None
    if args.now:
        now = datetime.fromisoformat(args.now.replace("Z", "+00:00"))
        if now.tzinfo is None:
            raise SystemExit("--now needs a timezone offset")

    window = timedelta(minutes=args.window_minutes)
    max_lateness = (
        timedelta(hours=args.max_lateness_hours)
        if args.max_lateness_hours is not None
        else MAX_EVENT_LATENESS
    )

    with catalog_module.connect() as connection:
        catalog = catalog_module.load(connection)
        ids = loader.Ids.load(connection)

        reference = ReferenceData(
            zone_codes=frozenset(z.code for z in catalog.zones),
            city_slugs=frozenset(c.slug for c in catalog.cities),
            source_codes=frozenset(s.code for s in catalog.sources),
        )
        zone_ids = {z.code: z.id for z in catalog.zones}
        zone_city = {z.code: z.city_slug for z in catalog.zones}

        received = 0
        valid_payloads: list[dict] = []
        rejections: list[Rejected] = []
        reason_counts: Counter[str] = Counter()
        seen_ids: set[str] = set()
        max_lag = 0
        earliest: datetime | None = None
        latest: datetime | None = None

        with args.input.open(encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if not line:
                    continue
                received += 1
                outcome = validate(
                    line, reference, now=now, seen_event_ids=seen_ids,
                    max_lateness=max_lateness,
                )
                if isinstance(outcome, Valid):
                    valid_payloads.append(outcome.payload)
                    moment = outcome.payload["event_time"]
                    earliest = moment if earliest is None else min(earliest, moment)
                    latest = moment if latest is None else max(latest, moment)
                else:
                    rejections.append(outcome)
                    reason_counts[outcome.reason_code] += 1

        # Raw events first: the curated windows reference nothing that is not
        # already stored, so a crash between the two leaves the lake consistent
        # rather than leaving aggregates with no evidence behind them.
        written = Counter()
        if not args.skip_raw:
            by_type: dict[str, list[dict]] = {}
            for payload in valid_payloads:
                by_type.setdefault(payload["event_type"], []).append(payload)

            for event_type, payloads in by_type.items():
                writer = loader.WRITERS[event_type]
                for start in range(0, len(payloads), args.batch_size):
                    chunk = payloads[start:start + args.batch_size]
                    written[event_type] += writer(connection, chunk, ids)
            connection.commit()

        if rejections:
            for start in range(0, len(rejections), args.batch_size):
                loader.write_dlq(
                    connection, rejections[start:start + args.batch_size], ids,
                    topic="local-file",
                )
            connection.commit()

        transform_stats: dict = {}
        windows = aggregate(
            valid_payloads, zone_ids=zone_ids, zone_city=zone_city, window=window,
            stats=transform_stats,
        )
        for start in range(0, len(windows), args.batch_size):
            loader.write_zone_metrics(connection, windows[start:start + args.batch_size])
        connection.commit()

        # A generated batch recomputes every window it covers, which would
        # overwrite a real AQI with the invented one for the same hour. The real
        # reading is still in the raw table, so it is re-applied rather than
        # lost — and the overlay is idempotent, so doing this every run is free.
        transform_stats["real_air_windows"] = provenance.overlay(
            connection, since=earliest
        )
        transform_stats["real_weather_windows"] = provenance.overlay_weather(
            connection, since=earliest
        )

        if earliest and latest:
            traffic_source = next(
                (s for s in catalog.sources if s.code == "synthetic-traffic"), None
            )
            source_id = traffic_source.id if traffic_source else None
            loader.write_quality_metrics(
                connection,
                source_id=source_id,
                stage="VALIDATE",
                window_start_at=earliest,
                window_end_at=latest + window,
                received=received,
                valid=len(valid_payloads),
                rejected=len(rejections),
                duplicate=reason_counts.get("DUPLICATE_EVENT_ID", 0),
                late=reason_counts.get("TIMESTAMP_TOO_OLD", 0),
                max_lag_seconds=max_lag or None,
            )

            # LOAD. The raw writers insert with ON CONFLICT DO NOTHING, so an
            # event that has already been stored is dropped without a word. That
            # is the correct behaviour — re-running a file must not duplicate
            # history — but it means a re-ingestion and a clean load looked
            # identical, and the gap between offered and written was the only
            # evidence either way. Nothing was measuring it.
            if not args.skip_raw:
                offered = len(valid_payloads)
                stored = sum(written.values())
                loader.write_quality_metrics(
                    connection,
                    source_id=source_id,
                    stage="LOAD",
                    window_start_at=earliest,
                    window_end_at=latest + window,
                    received=offered,
                    valid=stored,
                    rejected=0,
                    # Not rejected — already present. The distinction matters:
                    # rejection means the record was refused, this means it was
                    # recognised as one already held.
                    duplicate=max(offered - stored, 0),
                    late=0,
                    max_lag_seconds=None,
                )

            # AGGREGATE — the schema's name for this stage, and the
            # function's. Two kinds of event vanish here and neither was counted:
            # one whose timestamp will not parse, and one for a zone code the
            # catalogue does not know. Both are silent — the window simply never
            # appears — so a mis-seeded zone could remove a junction from the
            # dashboard with nothing anywhere recording it.
            dropped = (
                transform_stats.get("dropped_no_timestamp", 0)
                + transform_stats.get("dropped_unknown_zone", 0)
            )
            seen = transform_stats.get("events_seen", len(valid_payloads))
            loader.write_quality_metrics(
                connection,
                source_id=source_id,
                stage="AGGREGATE",
                window_start_at=earliest,
                window_end_at=latest + window,
                received=seen,
                valid=max(seen - dropped, 0),
                rejected=transform_stats.get("dropped_unknown_zone", 0),
                duplicate=0,
                late=transform_stats.get("dropped_no_timestamp", 0),
                max_lag_seconds=None,
            )
            connection.commit()

    if not args.quiet:
        print(json.dumps({
            "records_received": received,
            "records_valid": len(valid_payloads),
            "records_rejected": len(rejections),
            "validity_ratio": round(len(valid_payloads) / received, 4) if received else None,
            "rejections_by_reason": dict(reason_counts),
            "raw_rows_written": dict(written),
            "curated_windows": len(windows),
            "transform": transform_stats,
        }, indent=2))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
