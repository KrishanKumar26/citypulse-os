"""Measure how good the anomaly detector actually is (PRD Phase 7 exit criterion).

    python -m intelligence.evaluate

A detector that reports anomalies is easy to build and impossible to trust
without this. The method:

  1. Learn baselines from real history, holding back an evaluation period.
  2. Take the held-back windows and inject anomalies at *known* positions with
     *known* magnitudes.
  3. Run the detector over the mixture, knowing which is which.
  4. Report precision, recall and F1 — measured, not asserted.

Why injection rather than hand-labelling: nobody has labelled this data, and
labelling it by eye would encode the same intuitions the detector uses, which
measures agreement with myself rather than correctness. An injected spike of a
known size is ground truth by construction.

What this measures honestly, and what it does not: it measures whether the
detector finds departures it was built to find, on synthetic data. It does not
establish that real city anomalies look like these injections. That distinction
is stated in docs/ML.md rather than glossed.
"""

from __future__ import annotations

import argparse
import random
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg
from psycopg.rows import dict_row

from generator.catalog import connect
from intelligence.detection import (
    Baseline,
    Detection,
    InsufficientData,
    detect,
    hour_of_week,
    learn_baseline,
)

# Metrics evaluated. AQI is excluded for the same reason it is not forecast: it
# arrives roughly once every six windows, so most windows have nothing to judge
# and the measured figures would describe the sampling, not the detector.
METRICS: tuple[str, ...] = ("occupancy_ratio", "average_speed_kph", "vehicle_count")

# Share of the timeline held back. Baselines must be learned from data that does
# not include the windows being judged, or the injections would shift the very
# baseline they are measured against.
HOLDOUT_FRACTION = 0.25

# Injection magnitudes, as multiples of the baseline. Spread deliberately: a 4x
# spike should obviously be caught, a 1.5x one is genuinely marginal, and
# reporting a single easy magnitude would flatter the detector.
SPIKE_MULTIPLIERS = (1.5, 2.0, 3.0, 4.0)
DROP_MULTIPLIERS = (0.6, 0.4, 0.25)

# Share of held-out windows that receive an injection.
INJECTION_RATE = 0.05


@dataclass(slots=True)
class Scores:
    true_positives: int = 0
    false_positives: int = 0
    false_negatives: int = 0
    true_negatives: int = 0
    declined: int = 0
    # Of the unlabelled windows flagged, how many coincided with a known
    # disturbance — rain, an open incident or a scheduled event. A flag that
    # lines up with a real cause is very likely a genuine anomaly the harness
    # simply had no label for, so this separates 'the detector is wrong' from
    # 'the scoring is harsh'.
    false_positives_with_cause: int = 0

    @property
    def precision(self) -> float | None:
        flagged = self.true_positives + self.false_positives
        return self.true_positives / flagged if flagged else None

    @property
    def recall(self) -> float | None:
        actual = self.true_positives + self.false_negatives
        return self.true_positives / actual if actual else None

    @property
    def f1(self) -> float | None:
        p, r = self.precision, self.recall
        if p is None or r is None or p + r == 0:
            return None
        return 2 * p * r / (p + r)

    @property
    def judged(self) -> int:
        return self.true_positives + self.false_positives + self.false_negatives + self.true_negatives


def load_history(connection: psycopg.Connection) -> dict[int, list[dict]]:
    with connection.cursor(row_factory=dict_row) as cursor:
        cursor.execute("""
            SELECT zm.zone_id, zm.window_start,
                   zm.occupancy_ratio, zm.average_speed_kph, zm.vehicle_count,
                   -- Known disturbances, used to test whether the detector's
                   -- unlabelled flags coincide with real causes.
                   zm.precipitation_mm_h, zm.active_incidents, zm.active_events
            FROM zone_metrics zm
            JOIN zones z ON z.id = zm.zone_id
            WHERE z.deleted_at IS NULL
            ORDER BY zm.zone_id, zm.window_start
        """)
        rows = cursor.fetchall()

    by_zone: dict[int, list[dict]] = defaultdict(list)
    for row in rows:
        by_zone[row["zone_id"]].append(row)
    return by_zone


def has_known_cause(row: dict) -> bool:
    """Whether something in the data could legitimately explain a deviation.

    Rain, an open incident or a scheduled event are exactly the conditions the
    platform exists to notice. A flagged window coinciding with one is far more
    likely a real anomaly the harness had no label for than a detector mistake.
    """
    rain = row.get("precipitation_mm_h")
    return bool(
        (rain is not None and float(rain) > 0.5)
        or (row.get("active_incidents") or 0) > 0
        or (row.get("active_events") or 0) > 0
    )


def value_of(row: dict, metric: str) -> float | None:
    raw = row.get(metric)
    return None if raw is None else float(raw)


def learn(
    windows: list[dict], metric: str, until: datetime
) -> dict[int, Baseline]:
    """Baselines per hour-of-week, from windows strictly before the boundary."""
    buckets: dict[int, list[float]] = defaultdict(list)
    for row in windows:
        if row["window_start"] >= until:
            continue
        value = value_of(row, metric)
        if value is not None:
            buckets[hour_of_week(row["window_start"])].append(value)

    baselines: dict[int, Baseline] = {}
    for bucket, values in buckets.items():
        baseline = learn_baseline(metric, bucket, values)
        if baseline is not None:
            baselines[bucket] = baseline
    return baselines


def evaluate_metric(
    by_zone: dict[int, list[dict]],
    metric: str,
    boundary: datetime,
    rng: random.Random,
) -> Scores:
    scores = Scores()

    for windows in by_zone.values():
        baselines = learn(windows, metric, boundary)
        if not baselines:
            continue

        for row in windows:
            if row["window_start"] < boundary:
                continue
            observed = value_of(row, metric)
            if observed is None:
                continue

            bucket = hour_of_week(row["window_start"])
            baseline = baselines.get(bucket)
            if baseline is None:
                continue

            # Decide, before detecting, whether this window is an injection.
            is_injected = rng.random() < INJECTION_RATE
            if is_injected:
                if rng.random() < 0.6:
                    observed = baseline.median * rng.choice(SPIKE_MULTIPLIERS)
                else:
                    observed = baseline.median * rng.choice(DROP_MULTIPLIERS)

            outcome = detect(observed, baseline)

            if isinstance(outcome, InsufficientData):
                # Not scored either way. Counting a declined judgement as a miss
                # would penalise the detector for the honesty PRD §15 requires.
                scores.declined += 1
                continue

            assert isinstance(outcome, Detection)
            if is_injected and outcome.is_anomaly:
                scores.true_positives += 1
            elif is_injected:
                scores.false_negatives += 1
            elif outcome.is_anomaly:
                # An unlabelled window the detector flagged. Counted as a false
                # positive, which is deliberately harsh: some of these are real
                # anomalies in the generated data, so the measured precision is
                # a floor rather than the true figure.
                scores.false_positives += 1
                if has_known_cause(row):
                    scores.false_positives_with_cause += 1
            else:
                scores.true_negatives += 1

    return scores


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="intelligence.evaluate")
    parser.add_argument("--seed", type=int, default=42,
                        help="Injection seed. Fixed so the figures are reproducible.")
    args = parser.parse_args(argv)

    rng = random.Random(args.seed)

    with connect() as connection:
        by_zone = load_history(connection)

    if not by_zone:
        raise SystemExit("No curated windows found; run the pipeline first.")

    starts = [row["window_start"] for windows in by_zone.values() for row in windows]
    earliest, latest = min(starts), max(starts)
    boundary = earliest + (latest - earliest) * (1 - HOLDOUT_FRACTION)

    print(f"Zones: {len(by_zone)}  windows: {sum(len(v) for v in by_zone.values()):,}")
    print(f"Baselines learned from {earliest.date()} to {boundary.date()}")
    print(f"Evaluated on {boundary.date()} to {latest.date()}")
    print(f"Injection rate: {INJECTION_RATE:.0%}  seed: {args.seed}\n")

    header = f"{'metric':<20}{'precision':>11}{'recall':>9}{'F1':>8}{'TP':>7}{'FP':>7}{'FN':>7}{'declined':>10}"
    print(header)
    print("-" * len(header))

    overall = Scores()
    for metric in METRICS:
        scores = evaluate_metric(by_zone, metric, boundary, random.Random(args.seed))
        overall.true_positives += scores.true_positives
        overall.false_positives += scores.false_positives
        overall.false_negatives += scores.false_negatives
        overall.true_negatives += scores.true_negatives
        overall.declined += scores.declined
        overall.false_positives_with_cause += scores.false_positives_with_cause

        def fmt(value: float | None) -> str:
            return "—" if value is None else f"{value:.3f}"

        print(f"{metric:<20}{fmt(scores.precision):>11}{fmt(scores.recall):>9}"
              f"{fmt(scores.f1):>8}{scores.true_positives:>7}{scores.false_positives:>7}"
              f"{scores.false_negatives:>7}{scores.declined:>10}")

    print("-" * len(header))
    print(f"{'overall':<20}"
          f"{'—' if overall.precision is None else f'{overall.precision:.3f}':>11}"
          f"{'—' if overall.recall is None else f'{overall.recall:.3f}':>9}"
          f"{'—' if overall.f1 is None else f'{overall.f1:.3f}':>8}"
          f"{overall.true_positives:>7}{overall.false_positives:>7}"
          f"{overall.false_negatives:>7}{overall.declined:>10}")

    print(f"\nWindows judged: {overall.judged:,}; declined for insufficient baseline: "
          f"{overall.declined:,}.")

    with_cause = overall.false_positives_with_cause
    if overall.false_positives:
        share = with_cause / overall.false_positives
        print(f"\nOf {overall.false_positives:,} unlabelled flags, {with_cause:,} ({share:.0%}) "
              f"coincided with rain, an open incident or a scheduled event.")
        adjusted = overall.true_positives / (overall.true_positives
                                             + (overall.false_positives - with_cause))
        print(f"Counting those as genuine — they are exactly what the platform exists to "
              f"notice —\ngives precision {adjusted:.3f} against the harsh {overall.precision:.3f}.")
        print("Both figures are reported because neither is the whole truth: the harsh one")
        print("assumes every unlabelled flag is wrong, the adjusted one assumes every flag")
        print("with a plausible cause is right.")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
