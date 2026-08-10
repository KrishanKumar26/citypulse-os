"""Batch jobs behind the intelligence layer (PRD §12, §13, §16).

    python -m intelligence.jobs baselines     # learn what normal looks like
    python -m intelligence.jobs detect        # judge recent windows
    python -m intelligence.jobs memory        # record situations and outcomes
    python -m intelligence.jobs correlations  # measure co-occurrence
    python -m intelligence.jobs all

Each writes to its own table and reads only curated data. The backend serves
what these produce and computes none of it, so anything a user sees was
deliberately derived and can be traced back to the windows behind it.
"""

from __future__ import annotations

import argparse
import sys
import uuid
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from psycopg.rows import dict_row

from common.db import execute_batched
from generator.catalog import connect
from intelligence.detection import (
    Baseline,
    Detection,
    InsufficientData,
    detect,
    hour_of_week,
    learn_baseline,
)

BASELINE_METRICS = ("occupancy_ratio", "average_speed_kph", "vehicle_count", "risk_score")

# How far back a detection run looks. Longer than the pipeline's window so a
# brief outage does not leave a gap nobody judges.
DETECT_LOOKBACK = timedelta(hours=6)

# How long after a situation its outcome is measured over. Two hours is long
# enough for a peak to develop and clear, short enough that the outcome is
# plausibly attributable to the situation rather than to whatever came next.
OUTCOME_HORIZON = timedelta(hours=2)

# A situation is recorded at most this often per zone, so a stable afternoon
# does not fill the memory with near-identical rows.
SITUATION_INTERVAL = timedelta(hours=1)


# ---------------------------------------------------------------------------
# Baselines
# ---------------------------------------------------------------------------

def learn_baselines(connection: psycopg.Connection, *, timezone_name: str = "Asia/Kolkata") -> int:
    """Learn per-zone, per-hour-of-week normals from all available history."""
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT zm.zone_id, zm.window_start, zm.occupancy_ratio,
                   zm.average_speed_kph, zm.vehicle_count, zm.risk_score
            FROM zone_metrics zm
            JOIN zones z ON z.id = zm.zone_id
            WHERE z.deleted_at IS NULL AND z.active
            ORDER BY zm.zone_id, zm.window_start
        """)
        rows = cursor.fetchall()

    if not rows:
        raise SystemExit("No curated windows; run the pipeline first.")

    buckets: dict[tuple[int, str, int], list[float]] = defaultdict(list)
    for row in rows:
        bucket = hour_of_week(row["window_start"], timezone_name)
        for metric in BASELINE_METRICS:
            value = row[metric]
            if value is not None:
                buckets[(row["zone_id"], metric, bucket)].append(float(value))

    learned_from = min(r["window_start"] for r in rows)
    learned_to = max(r["window_start"] for r in rows)

    payload = []
    for (zone_id, metric, bucket), values in buckets.items():
        baseline = learn_baseline(metric, bucket, values)
        if baseline is None:
            continue
        payload.append((
            zone_id, metric, bucket,
            round(baseline.median, 4), round(baseline.mad, 4),
            round(baseline.p10, 4), round(baseline.p90, 4),
            baseline.sample_count, learned_from, learned_to,
        ))

    execute_batched(connection, """
        INSERT INTO zone_baselines (zone_id, metric, hour_of_week, median_value, mad,
            p10, p90, sample_count, learned_from, learned_to)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (zone_id, metric, hour_of_week) DO UPDATE SET
            median_value = EXCLUDED.median_value,
            mad          = EXCLUDED.mad,
            p10          = EXCLUDED.p10,
            p90          = EXCLUDED.p90,
            sample_count = EXCLUDED.sample_count,
            learned_from = EXCLUDED.learned_from,
            learned_to   = EXCLUDED.learned_to,
            computed_at  = now()
    """, payload)
    connection.commit()
    return len(payload)


def load_baselines(connection: psycopg.Connection) -> dict[tuple[int, str, int], Baseline]:
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("SELECT * FROM zone_baselines")
        return {
            (r["zone_id"], r["metric"], r["hour_of_week"]): Baseline(
                metric=r["metric"], hour_of_week=r["hour_of_week"],
                median=float(r["median_value"]), mad=float(r["mad"]),
                p10=float(r["p10"] or 0), p90=float(r["p90"] or 0),
                sample_count=r["sample_count"])
            for r in cursor.fetchall()
        }


# ---------------------------------------------------------------------------
# Detection
# ---------------------------------------------------------------------------

#: How each metric is named inside the sentence a duty officer reads.
#:
#: These go into `explanation`, which is stored and rendered verbatim on the
#: Command Center, so they are the product's words rather than the schema's.
#: "Composite risk" and "road occupancy" are the column names; neither is a
#: phrase someone arrives already holding, and the frontend cannot fix them
#: because by then they are inside a sentence in the database.
METRIC_LABELS = {
    "occupancy_ratio": "How full the roads are",
    "average_speed_kph": "Average speed",
    "vehicle_count": "The number of vehicles",
    "risk_score": "Overall risk",
}


def detect_anomalies(connection: psycopg.Connection, *, lookback: timedelta = DETECT_LOOKBACK) -> tuple[int, int]:
    """Judge recent windows against the learned baselines.

    Returns (anomalies written, windows declined for insufficient baseline).
    """
    baselines = load_baselines(connection)
    if not baselines:
        raise SystemExit("No baselines learned yet. Run: python -m intelligence.jobs baselines")

    since = datetime.now(timezone.utc) - lookback

    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT zm.zone_id, z.city_id, zm.window_start, zm.demo_data,
                   zm.occupancy_ratio, zm.average_speed_kph, zm.vehicle_count, zm.risk_score
            FROM zone_metrics zm
            JOIN zones z ON z.id = zm.zone_id
            WHERE z.deleted_at IS NULL AND z.active AND zm.window_start >= %s
            ORDER BY zm.window_start
        """, (since,))
        rows = cursor.fetchall()

    payload = []
    declined = 0
    for row in rows:
        bucket = hour_of_week(row["window_start"])
        for metric in BASELINE_METRICS:
            observed = row[metric]
            if observed is None:
                continue
            baseline = baselines.get((row["zone_id"], metric, bucket))
            if baseline is None:
                declined += 1
                continue

            outcome = detect(float(observed), baseline, metric_label=METRIC_LABELS[metric])
            if isinstance(outcome, InsufficientData):
                declined += 1
                continue
            if not outcome.is_anomaly:
                continue

            payload.append((
                str(uuid.uuid4()), row["zone_id"], row["city_id"], metric,
                str(outcome.anomaly_type), str(outcome.severity), row["window_start"],
                round(float(observed), 4), round(baseline.median, 4), round(baseline.mad, 4),
                outcome.deviation_score, outcome.percent_change,
                baseline.sample_count, outcome.explanation, row["demo_data"],
            ))

    execute_batched(connection, """
        INSERT INTO anomalies (uid, zone_id, city_id, metric, anomaly_type, severity,
            window_start, observed_value, baseline_value, baseline_mad,
            deviation_score, percent_change, baseline_samples, explanation, demo_data)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (zone_id, metric, window_start) DO NOTHING
    """, payload)
    connection.commit()
    return len(payload), declined


# ---------------------------------------------------------------------------
# City Memory
# ---------------------------------------------------------------------------

def rain_band(mm_h: float | None) -> str:
    if mm_h is None or mm_h <= 0.1:
        return "NONE"
    if mm_h < 2.5:
        return "LIGHT"
    if mm_h < 10.0:
        return "MODERATE"
    return "HEAVY"


def hour_band(moment: datetime, timezone_name: str = "Asia/Kolkata") -> str:
    hour = moment.astimezone(ZoneInfo(timezone_name)).hour
    if hour < 6:
        return "OVERNIGHT"
    if hour < 11:
        return "MORNING_PEAK"
    if hour < 16:
        return "MIDDAY"
    if hour < 21:
        return "EVENING_PEAK"
    return "EVENING"


def incident_band(count: int) -> str:
    if count == 0:
        return "NONE"
    return "SOME" if count <= 2 else "MANY"


def build_memory(connection: psycopg.Connection, *, timezone_name: str = "Asia/Kolkata") -> int:
    """Record past situations together with what actually followed them.

    The outcome is measured from the data, not predicted: peak occupancy, lowest
    speed and peak risk over the two hours after the situation. That is what
    makes a recalled situation worth anything — it reports what happened, and a
    model's opinion would just be the forecast again.
    """
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT zm.zone_id, z.city_id, zm.window_start, zm.demo_data,
                   zm.occupancy_ratio, zm.average_speed_kph, zm.risk_score,
                   zm.precipitation_mm_h, zm.active_incidents, zm.active_events,
                   zm.congestion_level
            FROM zone_metrics zm
            JOIN zones z ON z.id = zm.zone_id
            WHERE z.deleted_at IS NULL AND z.active
            ORDER BY zm.zone_id, zm.window_start
        """)
        rows = cursor.fetchall()

    by_zone: dict[int, list[dict]] = defaultdict(list)
    for row in rows:
        by_zone[row["zone_id"]].append(row)

    payload = []
    for windows in by_zone.values():
        last_recorded: datetime | None = None

        for index, row in enumerate(windows):
            if row["occupancy_ratio"] is None or row["congestion_level"] is None:
                continue
            moment = row["window_start"]
            if last_recorded is not None and moment - last_recorded < SITUATION_INTERVAL:
                continue

            # The outcome window must be complete, or the "what happened next"
            # would be measured over a truncated period and understate the peak.
            horizon_end = moment + OUTCOME_HORIZON
            following = [
                w for w in windows[index + 1:]
                if moment < w["window_start"] <= horizon_end
            ]
            if not following or following[-1]["window_start"] < horizon_end - timedelta(minutes=10):
                continue

            occupancies = [float(w["occupancy_ratio"]) for w in following if w["occupancy_ratio"] is not None]
            speeds = [float(w["average_speed_kph"]) for w in following if w["average_speed_kph"] is not None]
            risks = [float(w["risk_score"]) for w in following if w["risk_score"] is not None]
            if not occupancies:
                continue

            start_occupancy = float(row["occupancy_ratio"])
            start_speed = float(row["average_speed_kph"]) if row["average_speed_kph"] is not None else None
            start_risk = float(row["risk_score"]) if row["risk_score"] is not None else None

            peak_occupancy = max(occupancies)
            min_speed = min(speeds) if speeds else None
            peak_risk = max(risks) if risks else None

            payload.append((
                str(uuid.uuid4()), row["zone_id"], row["city_id"], moment,
                rain_band(float(row["precipitation_mm_h"]) if row["precipitation_mm_h"] is not None else None),
                "WEEKEND" if moment.astimezone(ZoneInfo(timezone_name)).weekday() >= 5 else "WEEKDAY",
                hour_band(moment, timezone_name),
                (row["active_events"] or 0) > 0,
                incident_band(row["active_incidents"] or 0),
                row["congestion_level"],
                round(start_occupancy, 4),
                None if start_speed is None else round(start_speed, 2),
                None if start_risk is None else round(start_risk, 2),
                int(OUTCOME_HORIZON.total_seconds() // 60),
                round(peak_occupancy, 4),
                None if min_speed is None else round(min_speed, 2),
                None if peak_risk is None else round(peak_risk, 2),
                round((peak_occupancy / start_occupancy - 1) * 100, 2) if start_occupancy else None,
                round((min_speed / start_speed - 1) * 100, 2) if start_speed and min_speed else None,
                round((peak_risk / start_risk - 1) * 100, 2) if start_risk and peak_risk else None,
                row["demo_data"],
            ))
            last_recorded = moment

    execute_batched(connection, """
        INSERT INTO situation_memory (uid, zone_id, city_id, occurred_at,
            rain_band, day_type, hour_band, had_event, incident_band, congestion_band,
            occupancy_at_start, speed_at_start, risk_at_start,
            outcome_horizon_minutes, peak_occupancy, min_speed_kph, peak_risk,
            occupancy_change_pct, speed_change_pct, risk_change_pct, demo_data)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (zone_id, occurred_at) DO NOTHING
    """, payload)
    connection.commit()
    return len(payload)


# ---------------------------------------------------------------------------
# Correlations
# ---------------------------------------------------------------------------

def conditions_of(row: dict) -> set[str]:
    """The coarse conditions a window satisfies.

    Deliberately few and coarse. Correlating fine-grained values would produce
    thousands of pairs with a handful of samples each — statistically noise
    dressed as insight.
    """
    present: set[str] = set()

    rain = float(row["precipitation_mm_h"]) if row["precipitation_mm_h"] is not None else 0.0
    if rain > 0.1:
        present.add("RAIN")
    if rain >= 10.0:
        present.add("HEAVY_RAIN")
    if (row["active_incidents"] or 0) > 0:
        present.add("INCIDENT_OPEN")
    if (row["active_events"] or 0) > 0:
        present.add("EVENT_ACTIVE")
    if row["congestion_level"] in ("HIGH", "CRITICAL"):
        present.add("HIGH_CONGESTION")
    if row["congestion_level"] == "CRITICAL":
        present.add("CRITICAL_CONGESTION")
    if row["aqi"] is not None and int(row["aqi"]) > 200:
        present.add("POOR_AIR")
    if row["risk_level"] in ("HIGH", "CRITICAL"):
        present.add("HIGH_RISK")
    if row["average_speed_kph"] is not None and float(row["average_speed_kph"]) < 15.0:
        present.add("SLOW_TRAFFIC")

    return present


# Pairs worth measuring: a cause the platform observes, against an outcome it
# cares about. Measuring every pair would mostly produce restatements of the
# same fact — HIGH_CONGESTION with SLOW_TRAFFIC is a definition, not a finding.
CORRELATION_PAIRS = [
    ("RAIN", "HIGH_CONGESTION"),
    ("RAIN", "SLOW_TRAFFIC"),
    ("HEAVY_RAIN", "CRITICAL_CONGESTION"),
    ("HEAVY_RAIN", "HIGH_RISK"),
    ("INCIDENT_OPEN", "HIGH_CONGESTION"),
    ("INCIDENT_OPEN", "SLOW_TRAFFIC"),
    ("EVENT_ACTIVE", "HIGH_CONGESTION"),
    ("EVENT_ACTIVE", "HIGH_RISK"),
    ("POOR_AIR", "HIGH_RISK"),
    ("HIGH_CONGESTION", "POOR_AIR"),
]

# Below this many co-occurrences a lift figure is not worth reporting.
MIN_CORRELATION_SUPPORT = 30


def compute_correlations(connection: psycopg.Connection) -> int:
    """Measure how often conditions occur together.

    Lift is P(B|A)/P(B): above 1 means A raises the odds of B. This is a
    measurement of co-occurrence, not a claim of causation, and the counts are
    stored so a reader can judge whether the figure means anything.
    """
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT z.city_id, zm.window_start, zm.congestion_level, zm.risk_level,
                   zm.precipitation_mm_h, zm.active_incidents, zm.active_events,
                   zm.aqi, zm.average_speed_kph
            FROM zone_metrics zm
            JOIN zones z ON z.id = zm.zone_id
            WHERE z.deleted_at IS NULL AND z.active
        """)
        rows = cursor.fetchall()

    if not rows:
        return 0

    by_city: dict[int, list[set[str]]] = defaultdict(list)
    span: dict[int, list[datetime]] = defaultdict(list)
    for row in rows:
        by_city[row["city_id"]].append(conditions_of(row))
        span[row["city_id"]].append(row["window_start"])

    payload = []
    for city_id, windows in by_city.items():
        total = len(windows)
        counts: dict[str, int] = defaultdict(int)
        for conditions in windows:
            for condition in conditions:
                counts[condition] += 1

        for a, b in CORRELATION_PAIRS:
            with_a = counts.get(a, 0)
            with_b = counts.get(b, 0)
            if with_a == 0 or with_b == 0:
                continue

            both = sum(1 for conditions in windows if a in conditions and b in conditions)
            if both < MIN_CORRELATION_SUPPORT:
                # Reporting a lift from a dozen windows would be presenting
                # noise as a finding.
                continue

            confidence = both / with_a
            prevalence = with_b / total
            lift = confidence / prevalence if prevalence else 0.0

            payload.append((
                city_id, a, b,
                round(lift, 4), round(both / total, 6), round(confidence, 6),
                with_a, both, total,
                min(span[city_id]), max(span[city_id]),
            ))

    execute_batched(connection, """
        INSERT INTO condition_correlations (city_id, condition_a, condition_b,
            lift, support, confidence, windows_with_a, windows_with_both,
            windows_total, computed_from, computed_to)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (city_id, condition_a, condition_b) DO UPDATE SET
            lift = EXCLUDED.lift, support = EXCLUDED.support,
            confidence = EXCLUDED.confidence,
            windows_with_a = EXCLUDED.windows_with_a,
            windows_with_both = EXCLUDED.windows_with_both,
            windows_total = EXCLUDED.windows_total,
            computed_from = EXCLUDED.computed_from,
            computed_to = EXCLUDED.computed_to,
            computed_at = now()
    """, payload)
    connection.commit()
    return len(payload)


# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="intelligence.jobs")
    parser.add_argument("job", choices=("baselines", "detect", "memory", "correlations", "all"))
    args = parser.parse_args(argv)

    with connect() as connection:
        if args.job in ("baselines", "all"):
            print(f"baselines:    {learn_baselines(connection):,} buckets learned")
        if args.job in ("detect", "all"):
            written, declined = detect_anomalies(connection)
            print(f"detect:       {written:,} anomalies, {declined:,} declined for thin baselines")
        if args.job in ("memory", "all"):
            print(f"memory:       {build_memory(connection):,} situations recorded")
        if args.job in ("correlations", "all"):
            print(f"correlations: {compute_correlations(connection):,} pairs measured")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
