"""Feature engineering for the traffic forecast (PRD §11).

Pure functions over ordered observations, with no database and no model. That
separation is what lets the leakage rules below be tested directly — and leakage
is the failure mode that matters here, because a model that has seen the future
scores beautifully in evaluation and forecasts nothing in production.

Two rules govern every feature:

1. **Only the past.** A feature for the window at time *t* may read observations
   at or before *t* and nothing after. Any average, any lag, any rolling
   statistic is computed over a strictly backward-looking slice.

2. **Only what is knowable at issue time.** Weather at *t + 60min* would be a
   superb predictor of congestion at *t + 60min*, and using it would be
   cheating: the platform does not have a weather forecast, so at the moment a
   prediction is issued that value does not exist.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Sequence
from zoneinfo import ZoneInfo


# Prediction horizons from PRD §11.
HORIZONS_MINUTES: tuple[int, ...] = (15, 30, 60, 180, 360)

# Curated windows are five minutes, so a horizon is a whole number of steps.
WINDOW_MINUTES = 5

# Lags offered to the model, in windows: 5, 15, 30 and 60 minutes back.
LAG_STEPS: tuple[int, ...] = (1, 3, 6, 12)

# Rolling means, in windows: 15 minutes, 1 hour, 3 hours.
ROLLING_STEPS: tuple[int, ...] = (3, 12, 36)


@dataclass(slots=True, frozen=True)
class Observation:
    """One curated window, as the feature builder needs it."""

    zone_id: int
    zone_type: str
    window_start: datetime
    occupancy_ratio: float | None
    average_speed_kph: float | None
    vehicle_count: int | None
    aqi: int | None
    risk_score: float | None
    precipitation_mm_h: float | None
    temperature_c: float | None
    active_incidents: int
    active_events: int


FEATURE_NAMES: tuple[str, ...] = (
    # Recent history of the target itself — by far the strongest signal for a
    # quantity as autocorrelated as traffic.
    *(f"lag_{n * WINDOW_MINUTES}min" for n in LAG_STEPS),
    *(f"rolling_mean_{n * WINDOW_MINUTES}min" for n in ROLLING_STEPS),
    "rolling_std_60min",
    # Rate of change: rising and falling congestion at the same level call for
    # different predictions, and a level-only model cannot tell them apart.
    "delta_15min",
    # Calendar. Traffic is far more a function of when it is than of anything else.
    "hour_sin",
    "hour_cos",
    "day_of_week",
    "is_weekend",
    "is_morning_peak",
    "is_evening_peak",
    # Conditions known at issue time.
    "precipitation_mm_h",
    "is_raining",
    "active_incidents",
    "active_events",
    # Static zone character.
    "zone_type_demand",
)


# Demand weighting per zone type, mirroring generator/patterns.py. A transit hub
# and a residential street behave differently at the same hour, and without this
# the model can only learn a city-wide average shape.
ZONE_TYPE_DEMAND: dict[str, float] = {
    "TRANSIT_HUB": 1.35,
    "COMMERCIAL": 1.20,
    "AIRPORT": 1.10,
    "MIXED": 1.00,
    "EDUCATIONAL": 0.95,
    "INDUSTRIAL": 0.90,
    "RESIDENTIAL": 0.80,
    "RECREATIONAL": 0.70,
}


def _value(observation: Observation, target: str) -> float | None:
    return {
        "occupancy_ratio": observation.occupancy_ratio,
        "average_speed_kph": observation.average_speed_kph,
        "vehicle_count": None if observation.vehicle_count is None else float(observation.vehicle_count),
        "aqi": None if observation.aqi is None else float(observation.aqi),
        "risk_score": observation.risk_score,
    }[target]


def _mean(values: Sequence[float]) -> float | None:
    return sum(values) / len(values) if values else None


def _std(values: Sequence[float]) -> float | None:
    if len(values) < 2:
        return None
    mean = sum(values) / len(values)
    return (sum((v - mean) ** 2 for v in values) / (len(values) - 1)) ** 0.5


def build_features(
    history: Sequence[Observation],
    index: int,
    target: str,
    *,
    timezone: str = "Asia/Kolkata",
) -> dict[str, float] | None:
    """Features for predicting `target` from the window at `history[index]`.

    `history` must be ascending by `window_start` for a single zone. Only
    `history[:index + 1]` is read — the slice is explicit rather than implied,
    because an off-by-one here is a leak that no test of the model's accuracy
    would ever reveal as a bug.

    Returns None when there is not enough history to fill the longest lag.
    Padding with zeros instead would teach the model that a cold start looks
    like an empty road.
    """
    if index < max(max(LAG_STEPS), max(ROLLING_STEPS)):
        return None

    current = history[index]
    past = history[: index + 1]

    current_value = _value(current, target)
    if current_value is None:
        return None

    features: dict[str, float] = {}

    for steps in LAG_STEPS:
        lagged = _value(past[index - steps], target)
        if lagged is None:
            return None
        features[f"lag_{steps * WINDOW_MINUTES}min"] = lagged

    for steps in ROLLING_STEPS:
        window = [v for v in (_value(o, target) for o in past[index - steps + 1 : index + 1])
                  if v is not None]
        mean = _mean(window)
        if mean is None:
            return None
        features[f"rolling_mean_{steps * WINDOW_MINUTES}min"] = mean

    hour_window = [v for v in (_value(o, target) for o in past[index - 11 : index + 1])
                   if v is not None]
    features["rolling_std_60min"] = _std(hour_window) or 0.0

    three_back = _value(past[index - 3], target)
    features["delta_15min"] = current_value - three_back if three_back is not None else 0.0

    # Calendar features in the city's own timezone: 09:00 local is the commute
    # everywhere, and 09:00 UTC is nothing in particular.
    local = current.window_start.astimezone(ZoneInfo(timezone))
    hour_fraction = local.hour + local.minute / 60.0
    # Cyclical encoding, so 23:55 and 00:05 are adjacent to the model rather
    # than maximally distant.
    import math

    features["hour_sin"] = math.sin(2 * math.pi * hour_fraction / 24)
    features["hour_cos"] = math.cos(2 * math.pi * hour_fraction / 24)
    features["day_of_week"] = float(local.weekday())
    features["is_weekend"] = 1.0 if local.weekday() >= 5 else 0.0
    features["is_morning_peak"] = 1.0 if 8 <= local.hour <= 10 else 0.0
    features["is_evening_peak"] = 1.0 if 17 <= local.hour <= 20 else 0.0

    features["precipitation_mm_h"] = current.precipitation_mm_h or 0.0
    features["is_raining"] = 1.0 if (current.precipitation_mm_h or 0.0) > 0 else 0.0
    features["active_incidents"] = float(current.active_incidents)
    features["active_events"] = float(current.active_events)
    features["zone_type_demand"] = ZONE_TYPE_DEMAND.get(current.zone_type, 1.0)

    return features


def build_training_rows(
    history: Sequence[Observation],
    target: str,
    horizon_minutes: int,
    *,
    timezone: str = "Asia/Kolkata",
) -> list[tuple[datetime, dict[str, float], float]]:
    """Feature/label pairs for one zone, one target and one horizon.

    The label is the target's value `horizon_minutes` later, taken from the
    actual window rather than interpolated. A gap in the data drops the row —
    inventing the label would be training on a number nobody measured.

    Returns (issue window start, features, label).
    """
    steps = horizon_minutes // WINDOW_MINUTES
    expected_gap = timedelta(minutes=horizon_minutes)
    rows: list[tuple[datetime, dict[str, float], float]] = []

    for index in range(len(history)):
        future_index = index + steps
        if future_index >= len(history):
            break

        future = history[future_index]
        # Windows are only comparable when they really are `horizon` apart. A
        # missing stretch would otherwise silently become a shorter horizon,
        # making the model look better at long ranges than it is.
        if future.window_start - history[index].window_start != expected_gap:
            continue

        label = _value(future, target)
        if label is None:
            continue

        features = build_features(history, index, target, timezone=timezone)
        if features is None:
            continue

        rows.append((history[index].window_start, features, label))

    return rows
