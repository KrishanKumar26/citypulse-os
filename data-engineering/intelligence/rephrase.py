"""Rebuild stored anomaly sentences in the current wording.

    python -m intelligence.rephrase [--dry-run]

The sentence a duty officer reads on the Command Center is composed when a
window is judged and stored on the row. So changing how it is worded does not
change anything already written: the detector only judges new windows, and the
feed shows the last six hours, so the old phrasing stays on screen for hours
after the change ships — on exactly the rows someone is looking at now.

This rewrites them. It is not a rewrite of history: `anomalies` stores the
observation, the baseline, the sample count and the percent change, so the
sentence is rebuilt from the evidence the detection already established, by the
same `explain()` the detector calls. Nothing is inferred and no verdict moves —
`deviation_score`, `severity` and `anomaly_type` are untouched.

Safe to run repeatedly: a row already in the current wording is rewritten to
the identical string, so the UPDATE is a no-op in effect. Run it after any
change to `explain()`.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import tuple_row  # noqa: E402

from intelligence.detection import explain  # noqa: E402
from intelligence.jobs import METRIC_LABELS  # noqa: E402


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="rephrase",
        description="Rebuild stored anomaly explanations in the current wording.",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Report what would change and write nothing.",
    )
    parser.add_argument(
        "--limit", type=int,
        help="Stop after this many rows. Absent means every row.",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    dsn = os.environ.get("CITYPULSE_PG_DSN")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1

    with psycopg.connect(dsn, row_factory=tuple_row) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT id, metric, anomaly_type, observed_value, baseline_value,
                       baseline_samples, percent_change, explanation
                  FROM anomalies
                 ORDER BY detected_at DESC
                """
                + (" LIMIT %s" if args.limit else ""),
                (args.limit,) if args.limit else (),
            )
            rows = cursor.fetchall()

        updates = []
        for (row_id, metric, kind, observed, baseline, samples,
             percent, current) in rows:
            # SUSTAINED detections have their own sentence and are not rebuilt
            # here; this function describes a single window against a baseline.
            if kind not in ("SPIKE", "DROP"):
                continue
            rebuilt = explain(
                metric=metric,
                label=METRIC_LABELS.get(metric, metric),
                observed=float(observed),
                baseline_median=float(baseline),
                samples=int(samples),
                direction="above" if kind == "SPIKE" else "below",
                percent_change=None if percent is None else float(percent),
            )
            if rebuilt != current:
                updates.append((rebuilt, row_id))

        print(f"  {len(rows)} rows read, {len(updates)} would change")
        if updates:
            print(f"  example: {updates[0][0][:120]}…")

        if args.dry_run:
            print("  --dry-run: nothing written")
            return 0

        if updates:
            with connection.cursor() as cursor:
                cursor.executemany(
                    "UPDATE anomalies SET explanation = %s WHERE id = %s",
                    updates,
                )
            connection.commit()
            print(f"  {len(updates)} rewritten")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
