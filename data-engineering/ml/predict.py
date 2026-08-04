"""Generate forecasts from the active model (PRD §11).

    python -m ml.predict

Loads the ACTIVE model run, builds features from each zone's most recent
windows, and writes one forecast per (zone, metric, horizon). The backend only
reads these rows — it never runs a model — so a prediction served to a user is
always one that was produced deliberately and can be scored later.

Confidence and interval come from the stored measured error for that exact
metric and horizon. Nothing here decides how confident to look; it reads what
the holdout said.
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from collections import defaultdict
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import numpy as np
import psycopg
from psycopg.rows import dict_row

from generator.catalog import connect
from ml.features import FEATURE_NAMES, Observation, build_features
from ml.model import RidgeModel, confidence_from_error, prediction_interval
from ml.train import MODEL_NAME, TARGET_SCALE

# Bands for the forecast's risk label, matching common/transforms.py so a
# predicted risk of 80 reads CRITICAL exactly as an observed 80 does.
RISK_BANDS: tuple[tuple[float, str], ...] = ((25.0, "NORMAL"), (50.0, "MODERATE"), (75.0, "HIGH"))

# Occupancy has its own bands (transforms.congestion_level), so a forecast of
# congestion is labelled on the congestion scale rather than the risk one.
OCCUPANCY_BANDS: tuple[tuple[float, str], ...] = (
    (0.55, "NORMAL"), (0.80, "MODERATE"), (1.00, "HIGH"))


def band(value: float, bands: tuple[tuple[float, str], ...]) -> str:
    for upper, label in bands:
        if value <= upper:
            return label
    return "CRITICAL"


def risk_label(target: str, value: float) -> str | None:
    if target == "risk_score":
        return band(value, RISK_BANDS)
    if target == "occupancy_ratio":
        return band(value, OCCUPANCY_BANDS)
    # Speed and volume have no severity scale of their own — a number is not
    # good or bad without a capacity to compare it against, and inventing a
    # band here would be asserting a judgement the platform has not made.
    return None


def load_active_models(connection: psycopg.Connection) -> tuple[dict, dict]:
    """The ACTIVE run and its per-(metric, horizon) fitted models."""
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT id, uid, model_version, features
            FROM model_runs WHERE model_name = %s AND status = 'ACTIVE'
        """, (MODEL_NAME,))
        run = cursor.fetchone()
        if run is None:
            raise SystemExit(
                f"No ACTIVE run for {MODEL_NAME}. Train one first: python -m ml.train")

        cursor.execute("""
            SELECT target_metric, horizon_minutes, mae, residual_std,
                   coefficients, intercept, feature_means, feature_scales
            FROM model_metrics
            WHERE model_run_id = %s AND coefficients IS NOT NULL
        """, (run["id"],))
        rows = cursor.fetchall()

    if not rows:
        raise SystemExit(
            "The active run has no stored coefficients — retrain so the model is persisted.")

    feature_names = tuple(run["features"])
    models: dict[tuple[str, int], tuple[RidgeModel, float, float]] = {}
    for row in rows:
        model = RidgeModel(
            feature_names=feature_names,
            coefficients=np.array(row["coefficients"], dtype=float),
            intercept=float(row["intercept"]),
            means=np.array(row["feature_means"], dtype=float),
            scales=np.array(row["feature_scales"], dtype=float),
            alpha=1.0,
        )
        models[(row["target_metric"], row["horizon_minutes"])] = (
            model, float(row["mae"]), float(row["residual_std"] or 0.0))

    return run, models


def load_recent(connection: psycopg.Connection, lookback_windows: int = 60) -> dict[int, list[Observation]]:
    """Enough recent history per zone to fill the longest lag."""
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT * FROM (
                SELECT zm.zone_id, z.zone_type, zm.window_start,
                       zm.occupancy_ratio, zm.average_speed_kph, zm.vehicle_count,
                       zm.aqi, zm.risk_score, zm.precipitation_mm_h, zm.temperature_c,
                       zm.active_incidents, zm.active_events,
                       row_number() OVER (PARTITION BY zm.zone_id ORDER BY zm.window_start DESC) AS rn
                FROM zone_metrics zm
                JOIN zones z ON z.id = zm.zone_id
                WHERE z.deleted_at IS NULL AND z.active
            ) ranked
            WHERE rn <= %s
            ORDER BY zone_id, window_start
        """, (lookback_windows,))
        rows = cursor.fetchall()

    by_zone: dict[int, list[Observation]] = defaultdict(list)
    for row in rows:
        by_zone[row["zone_id"]].append(Observation(
            zone_id=row["zone_id"], zone_type=row["zone_type"],
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


def describe(factors: list[dict], target: str) -> list[dict]:
    """Turn raw feature effects into something a person can read.

    PRD §11 asks for contributing factors and §15 requires an explanation to
    cite real data. These are the actual arithmetic terms of a linear model, not
    a narrative written around the answer.
    """
    readable = {
        "lag_5min": "conditions five minutes ago",
        "lag_15min": "conditions fifteen minutes ago",
        "lag_30min": "conditions half an hour ago",
        "lag_60min": "conditions an hour ago",
        "rolling_mean_15min": "the last fifteen minutes on average",
        "rolling_mean_60min": "the last hour on average",
        "rolling_mean_180min": "the last three hours on average",
        "rolling_std_60min": "how variable the last hour has been",
        "delta_15min": "the direction conditions are moving",
        "hour_sin": "time of day",
        "hour_cos": "time of day",
        "day_of_week": "day of the week",
        "is_weekend": "it being a weekend",
        "is_morning_peak": "the morning peak",
        "is_evening_peak": "the evening peak",
        "precipitation_mm_h": "rainfall",
        "is_raining": "rain",
        "active_incidents": "open incidents",
        "active_events": "scheduled events",
        "zone_type_demand": "the kind of zone this is",
    }
    return [
        {
            "factor": readable.get(f["feature"], f["feature"]),
            "feature": f["feature"],
            "value": f["value"],
            "direction": f["direction"],
            "effect": f["effect"],
        }
        for f in factors
    ]


def _issue(observations, index, based_on, zone_id, run, models, rows) -> int:
    """Produce every (metric, horizon) forecast from one issue point.

    Returns how many were skipped for want of history.
    """
    import json
    import uuid as _uuid

    skipped = 0
    for (target, horizon), (model, mae, residual_std) in models.items():
        features = build_features(observations, index, target)
        if features is None:
            # Not enough history behind this point. Emitting a forecast anyway
            # would mean predicting from padding.
            skipped += 1
            continue

        predicted = model.predict_one(features)
        # A linear model can extrapolate below zero; the clamp keeps the stored
        # value physically meaningful.
        if target in ("occupancy_ratio", "vehicle_count", "risk_score"):
            predicted = max(0.0, predicted)
        if target == "risk_score":
            predicted = min(100.0, predicted)

        confidence = confidence_from_error(predicted, mae, typical_scale=TARGET_SCALE[target])
        lower, upper = prediction_interval(predicted, residual_std)

        rows.append((
            str(_uuid.uuid4()), zone_id, run["id"], target, horizon,
            based_on + timedelta(minutes=horizon), based_on,
            based_on,  # issued_at: the moment the model was allowed to know
            round(predicted, 4), round(max(0.0, lower), 4), round(upper, 4),
            confidence, risk_label(target, predicted),
            json.dumps(describe(model.contributions(features), target)),
        ))
    return skipped


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="ml.predict")
    parser.add_argument("--dry-run", action="store_true")
    parser.add_argument(
        "--backfill-hours", type=float,
        help="Also issue forecasts from historical windows going back this many "
             "hours. Used to build accuracy history: their target times have "
             "already passed, so ml.score can compare them against what happened.",
    )
    parser.add_argument("--backfill-step-minutes", type=int, default=60)
    args = parser.parse_args(argv)

    with connect() as connection:
        run, models = load_active_models(connection)
        # A backfill needs history behind each issue point too, so the lookback
        # covers the backfill span plus the longest lag.
        lookback = 60 + (int(args.backfill_hours * 12) if args.backfill_hours else 0)
        by_zone = load_recent(connection, lookback_windows=lookback)

        # Issue points: the newest window, plus historical ones when backfilling.
        #
        # A backfilled forecast is honest only because `build_features` slices
        # strictly backwards from its issue point — the model sees nothing after
        # the moment it is pretending to be. It is still not identical to having
        # predicted live, and issue points falling inside the training period
        # would be scored against conditions the model had already learned.
        offsets = [0]
        if args.backfill_hours:
            step = max(1, args.backfill_step_minutes // 5)
            count = int(args.backfill_hours * 60 / args.backfill_step_minutes)
            offsets.extend(step * (i + 1) for i in range(count))

        rows: list[tuple] = []
        skipped = 0
        for zone_id, observations in by_zone.items():
            for offset in offsets:
                index = len(observations) - 1 - offset
                if index < 0:
                    continue
                skipped += _issue(observations, index, observations[index].window_start,
                                  zone_id, run, models, rows)

        if args.dry_run:
            print(f"Would write {len(rows)} forecasts from {len(offsets)} issue point(s) "
                  f"({skipped} skipped for lack of history)")
            for row in rows[:3]:
                print(f"  zone={row[1]} {row[3]} +{row[4]}min -> {row[8]} "
                      f"(confidence {row[11]}, {row[12]})")
            return 0

        with connection.cursor() as cursor:
            cursor.executemany("""
                INSERT INTO forecasts (uid, zone_id, model_run_id, target_metric,
                    horizon_minutes, target_time, based_on_window, issued_at,
                    predicted_value, lower_bound, upper_bound, confidence,
                    risk_level, contributing_factors)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (zone_id, target_metric, horizon_minutes, issued_at) DO NOTHING
            """, rows)
        connection.commit()

        print(f"Wrote {len(rows)} forecasts from run {run['uid']} ({run['model_version']}) "
              f"across {len(offsets)} issue point(s); {skipped} skipped.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
