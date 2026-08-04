"""Train and evaluate the forecast baseline (PRD §11).

    python -m ml.train --model-version v1
    python -m ml.train --dry-run          # evaluate, print, write nothing

What this does, in order:

    load observations → split by time → fit per (metric, horizon)
    → measure on the holdout → persist the run and its measured error

The split is *temporal*, never random. A random split would put 10:05 in
training and 10:10 in test for the same zone, and lag features would then carry
the answer straight across — producing an MAE that looks superb and a model that
cannot forecast anything. Every number this script reports is measured on data
recorded strictly after everything it trained on.
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from psycopg.rows import dict_row

from generator.catalog import connect
from ml.features import (
    FEATURE_NAMES,
    HORIZONS_MINUTES,
    Observation,
    build_training_rows,
)
from ml.model import Evaluation, RidgeModel, evaluate, fit_ridge

MODEL_NAME = "traffic-baseline"
ALGORITHM = "ridge-regression"

# Targets the model forecasts, against PRD §11's list of five.
#
# Four are here: congestion (occupancy), average speed, vehicle volume and risk
# level. Two are deliberately absent, and the reasons differ:
#
#   crowd intensity — the platform has no crowd sensor. Predicting a quantity
#       nobody measures would be a fabrication that no evaluation could catch,
#       because there would be no actual to score against.
#
#   AQI — measured, but on a slower cadence than traffic. Air quality arrives
#       roughly once every six curated windows, so at a five-minute grain the
#       lag features are null far more often than not and the model has almost
#       nothing to learn from. Forward-filling would manufacture the density:
#       the same reading repeated six times would look like six confirmations
#       of a stable value, and the measured error would be flattered by a
#       target that barely moves. AQI forecasting belongs on its own grain and
#       is left out rather than faked at this one.
TARGETS: tuple[str, ...] = (
    "occupancy_ratio",
    "average_speed_kph",
    "vehicle_count",
    "risk_score",
)

# Typical magnitude per target, for turning an absolute error into a comparable
# confidence. Fixed rather than computed from the current data so confidence
# does not silently shift meaning when a quiet week is loaded.
TARGET_SCALE: dict[str, float] = {
    "occupancy_ratio": 1.0,      # 1.0 == rated capacity
    "average_speed_kph": 48.0,   # free-flow speed
    "vehicle_count": 1500.0,     # a busy zone's five-minute count
    "risk_score": 100.0,         # the score is 0-100 by definition
}

# Share of the timeline held back for evaluation.
HOLDOUT_FRACTION = 0.25


def load_observations(connection: psycopg.Connection) -> dict[int, list[Observation]]:
    """Curated windows per zone, ascending by time."""
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT zm.zone_id, z.zone_type, zm.window_start,
                   zm.occupancy_ratio, zm.average_speed_kph, zm.vehicle_count,
                   zm.aqi, zm.risk_score, zm.precipitation_mm_h, zm.temperature_c,
                   zm.active_incidents, zm.active_events
            FROM zone_metrics zm
            JOIN zones z ON z.id = zm.zone_id
            WHERE z.deleted_at IS NULL
            ORDER BY zm.zone_id, zm.window_start
        """)
        rows = cursor.fetchall()

    by_zone: dict[int, list[Observation]] = defaultdict(list)
    for row in rows:
        by_zone[row["zone_id"]].append(Observation(
            zone_id=row["zone_id"],
            zone_type=row["zone_type"],
            window_start=row["window_start"],
            occupancy_ratio=float(row["occupancy_ratio"]) if row["occupancy_ratio"] is not None else None,
            average_speed_kph=float(row["average_speed_kph"]) if row["average_speed_kph"] is not None else None,
            vehicle_count=row["vehicle_count"],
            aqi=row["aqi"],
            risk_score=float(row["risk_score"]) if row["risk_score"] is not None else None,
            precipitation_mm_h=float(row["precipitation_mm_h"]) if row["precipitation_mm_h"] is not None else None,
            temperature_c=float(row["temperature_c"]) if row["temperature_c"] is not None else None,
            active_incidents=row["active_incidents"] or 0,
            active_events=row["active_events"] or 0,
        ))
    return by_zone


def split_point(by_zone: dict[int, list[Observation]]) -> datetime:
    """The instant that divides training from evaluation.

    One boundary for every zone rather than a per-zone percentile: zones share
    weather and city events, so splitting each independently would let a rainy
    hour be training data for one zone and test data for its neighbour.
    """
    starts = [o.window_start for obs in by_zone.values() for o in obs]
    if not starts:
        raise SystemExit("no curated windows found; run the pipeline first")
    earliest, latest = min(starts), max(starts)
    span = latest - earliest
    return earliest + span * (1 - HOLDOUT_FRACTION)


def train_target(
    by_zone: dict[int, list[Observation]],
    target: str,
    horizon: int,
    boundary: datetime,
) -> tuple[RidgeModel, Evaluation] | None:
    """Fit one (metric, horizon) pair and score it on the holdout."""
    train_rows: list[dict[str, float]] = []
    train_labels: list[float] = []
    test_rows: list[dict[str, float]] = []
    test_labels: list[float] = []

    for observations in by_zone.values():
        for issued_at, features, label in build_training_rows(observations, target, horizon):
            # A row belongs to the holdout only if the *label* falls after the
            # boundary too. Judging by issue time alone would let a row issued
            # just before the split predict into the test period using a model
            # that had already seen it.
            label_time = issued_at + timedelta(minutes=horizon)
            if label_time <= boundary:
                train_rows.append(features)
                train_labels.append(label)
            elif issued_at > boundary:
                test_rows.append(features)
                test_labels.append(label)
            # Rows straddling the boundary are dropped entirely — they belong to
            # neither side without leaking.

    if len(train_rows) <= len(FEATURE_NAMES) or not test_rows:
        return None

    model = fit_ridge(train_rows, train_labels, FEATURE_NAMES)
    scoring = evaluate(model, test_rows, test_labels, persistence_feature="lag_5min")
    return model, scoring


def persist(
    connection: psycopg.Connection,
    results: dict[tuple[str, int], tuple[RidgeModel, Evaluation]],
    *,
    version: str,
    boundary: datetime,
    earliest: datetime,
    latest: datetime,
) -> str:
    """Write the run and its measured error, and make it the active model."""
    training_rows = sum(e.sample_count for _, e in results.values())
    evaluation_rows = sum(e.sample_count for _, e in results.values())
    run_uid = str(uuid.uuid4())

    with connection.cursor(row_factory=dict_row) as cursor:
        # Only one model may be ACTIVE, so the previous one retires first.
        cursor.execute(
            "UPDATE model_runs SET status = 'RETIRED' WHERE model_name = %s AND status = 'ACTIVE'",
            (MODEL_NAME,))

        cursor.execute("""
            INSERT INTO model_runs (uid, model_name, model_version, algorithm,
                trained_from, trained_to, evaluated_from, evaluated_to,
                training_rows, evaluation_rows, features, hyperparameters, status, notes)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 'ACTIVE', %s)
            RETURNING id
        """, (
            run_uid, MODEL_NAME, version, ALGORITHM,
            earliest, boundary, boundary, latest,
            training_rows, evaluation_rows,
            json.dumps(list(FEATURE_NAMES)),
            json.dumps({"alpha": 1.0, "holdout_fraction": HOLDOUT_FRACTION}),
            f"Temporal holdout: trained to {boundary.isoformat()}, evaluated after.",
        ))
        run_id = cursor.fetchone()["id"]

        for (target, horizon), (model, scoring) in sorted(results.items()):
            cursor.execute("""
                INSERT INTO model_metrics (model_run_id, target_metric, horizon_minutes,
                    mae, mape, rmse, baseline_mae, sample_count,
                    coefficients, intercept, feature_means, feature_scales, residual_std)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            """, (
                run_id, target, horizon,
                round(scoring.mae, 4),
                None if scoring.mape is None else round(min(scoring.mape, 9999.0), 4),
                round(scoring.rmse, 4),
                round(scoring.baseline_mae, 4),
                scoring.sample_count,
                # The fitted model itself. Without this a forecast could only be
                # produced by refitting, which would make every prediction depend
                # on data that arrived after the model was evaluated.
                json.dumps([round(c, 8) for c in model.coefficients.tolist()]),
                round(model.intercept, 6),
                json.dumps([round(m, 8) for m in model.means.tolist()]),
                json.dumps([round(s, 8) for s in model.scales.tolist()]),
                round(scoring.residual_std, 4),
            ))
    connection.commit()
    return run_uid


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ml.train")
    parser.add_argument("--model-version", default="v1")
    parser.add_argument("--dry-run", action="store_true",
                        help="Evaluate and print without writing to the database.")
    args = parser.parse_args(argv)

    with connect() as connection:
        by_zone = load_observations(connection)
        boundary = split_point(by_zone)
        starts = [o.window_start for obs in by_zone.values() for o in obs]
        earliest, latest = min(starts), max(starts)

        print(f"Zones: {len(by_zone)}  windows: {sum(len(v) for v in by_zone.values()):,}")
        print(f"Timeline: {earliest.date()} → {latest.date()}")
        print(f"Holdout boundary: {boundary.isoformat(timespec='minutes')} "
              f"(last {int(HOLDOUT_FRACTION * 100)}% held back)\n")

        results: dict[tuple[str, int], tuple[RidgeModel, Evaluation]] = {}
        header = f"{'metric':<20}{'horizon':>8}{'MAE':>10}{'MAPE%':>9}{'baseline':>10}{'vs base':>9}{'n':>9}"
        print(header)
        print("-" * len(header))

        for target in TARGETS:
            for horizon in HORIZONS_MINUTES:
                outcome = train_target(by_zone, target, horizon, boundary)
                if outcome is None:
                    print(f"{target:<20}{horizon:>8}{'  (insufficient data)':>38}")
                    continue

                model, scoring = outcome
                results[(target, horizon)] = (model, scoring)
                improvement = (1 - scoring.mae / scoring.baseline_mae) * 100
                mape = "—" if scoring.mape is None else f"{scoring.mape:.1f}"
                print(f"{target:<20}{horizon:>8}{scoring.mae:>10.4f}{mape:>9}"
                      f"{scoring.baseline_mae:>10.4f}{improvement:>8.1f}%{scoring.sample_count:>9,}")

        if not results:
            raise SystemExit("\nNo model could be fitted — is there enough history loaded?")

        beaten = sum(1 for _, e in results.values() if e.beats_baseline)
        print(f"\n{beaten} of {len(results)} models beat persistence.")

        if args.dry_run:
            print("Dry run — nothing written.")
            return 0

        run_uid = persist(connection, results, version=args.model_version,
                          boundary=boundary, earliest=earliest, latest=latest)
        print(f"Stored model run {run_uid} as ACTIVE ({len(results)} measured metrics).")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
