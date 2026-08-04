"""Score past forecasts against what actually happened (PRD §11).

    python -m ml.score

Measured error at training time is a claim about the past. This closes the loop:
once a forecast's target time has passed and the real window exists, the two are
compared and the result stored. Without it the platform could report a
confidence forever without ever checking whether it was earned — which is
indistinguishable from making it up.

Deliberately independent of the training run. A model that degrades after
deployment — because traffic patterns shifted, or a feed changed — will show up
here as production error diverging from holdout error, and nothing in the
training pipeline could ever notice that.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from psycopg.rows import dict_row

from common.db import execute_batched
from generator.catalog import connect

# Below this the actual is treated as effectively zero and percentage error is
# left null. A 300% error against an actual of 0.01 is arithmetically true and
# analytically useless; averaging it in would swamp every summary it enters.
NEAR_ZERO = 1e-6


def score_pending(connection: psycopg.Connection, *, limit: int) -> int:
    """Match unscored forecasts to the windows that came to pass."""
    with connection.cursor(row_factory=dict_row) as cursor:
        # The join finds the curated window covering each forecast's target time.
        # Matching on containment rather than equality because a forecast's
        # target lands wherever the horizon puts it, not on a window boundary.
        cursor.execute("""
            SELECT f.id AS forecast_id, f.model_run_id, f.zone_id, f.target_metric,
                   f.horizon_minutes, f.target_time, f.predicted_value,
                   f.lower_bound, f.upper_bound,
                   CASE f.target_metric
                       WHEN 'occupancy_ratio'   THEN zm.occupancy_ratio
                       WHEN 'average_speed_kph' THEN zm.average_speed_kph
                       WHEN 'vehicle_count'     THEN zm.vehicle_count
                       WHEN 'risk_score'        THEN zm.risk_score
                   END AS actual_value
            FROM forecasts f
            JOIN zone_metrics zm
              ON zm.zone_id = f.zone_id
             AND f.target_time >= zm.window_start
             AND f.target_time <  zm.window_end
            LEFT JOIN forecast_accuracy fa ON fa.forecast_id = f.id
            WHERE fa.id IS NULL
              AND f.target_time < now()
            LIMIT %s
        """, (limit,))
        pending = cursor.fetchall()

        rows = []
        for row in pending:
            actual = row["actual_value"]
            if actual is None:
                # The window exists but this metric was not measured in it.
                # Scoring against a null would invent an error.
                continue

            actual = float(actual)
            predicted = float(row["predicted_value"])
            absolute = abs(predicted - actual)
            percentage = (
                round(absolute / abs(actual) * 100, 4) if abs(actual) > NEAR_ZERO else None
            )

            lower = row["lower_bound"]
            upper = row["upper_bound"]
            within = (
                None if lower is None or upper is None
                else float(lower) <= actual <= float(upper)
            )

            rows.append((
                row["forecast_id"], row["model_run_id"], row["zone_id"],
                row["target_metric"], row["horizon_minutes"], row["target_time"],
                round(predicted, 4), round(actual, 4), round(absolute, 4),
                percentage, within,
            ))

        if rows:
            execute_batched(connection, """
                INSERT INTO forecast_accuracy (forecast_id, model_run_id, zone_id,
                    target_metric, horizon_minutes, target_time,
                    predicted_value, actual_value, absolute_error,
                    percentage_error, within_bounds)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (forecast_id) DO NOTHING
            """, rows)
    connection.commit()
    return len(rows)


def report(connection: psycopg.Connection) -> None:
    """Production error next to the error measured at training time.

    Side by side because the comparison is the point: holdout MAE says how well
    the model did on data it had not seen *then*, and production MAE says how it
    is doing *now*. A widening gap is the signal that the model has gone stale,
    and neither number alone can show it.
    """
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT fa.target_metric, fa.horizon_minutes,
                   count(*) AS scored,
                   round(avg(fa.absolute_error), 4) AS production_mae,
                   mm.mae AS holdout_mae,
                   round(100.0 * count(*) FILTER (WHERE fa.within_bounds) / count(*), 1)
                       AS pct_within_interval
            FROM forecast_accuracy fa
            JOIN model_metrics mm
              ON mm.model_run_id = fa.model_run_id
             AND mm.target_metric = fa.target_metric
             AND mm.horizon_minutes = fa.horizon_minutes
            GROUP BY fa.target_metric, fa.horizon_minutes, mm.mae
            ORDER BY fa.target_metric, fa.horizon_minutes
        """)
        rows = cursor.fetchall()

    if not rows:
        print("Nothing scored yet — forecasts need their target time to pass first.")
        return

    header = f"{'metric':<20}{'horizon':>8}{'scored':>8}{'prod MAE':>11}{'holdout':>10}{'in 95% CI':>11}"
    print(header)
    print("-" * len(header))
    for row in rows:
        print(f"{row['target_metric']:<20}{row['horizon_minutes']:>8}{row['scored']:>8}"
              f"{float(row['production_mae']):>11.4f}{float(row['holdout_mae']):>10.4f}"
              f"{float(row['pct_within_interval'] or 0):>10.1f}%")

    print("\n'in 95% CI' is the honest test of the advertised confidence: an interval")
    print("that claims 95% coverage and delivers far less was never worth its label.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ml.score")
    parser.add_argument("--limit", type=int, default=50_000)
    parser.add_argument("--report-only", action="store_true")
    args = parser.parse_args(argv)

    with connect() as connection:
        if not args.report_only:
            scored = score_pending(connection, limit=args.limit)
            print(f"Scored {scored} forecasts against actuals.\n")
        report(connection)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
