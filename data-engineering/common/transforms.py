"""Derivations applied to validated events: categories, levels and risk.

Every function here is pure and total — same input, same output, no I/O, no
clock. That is what lets the Spark job and the local runner share them, and
what makes the numbers on the dashboard reproducible: given the same window of
events, the risk score is the same today and next month.

These are also the "assumptions" the PRD requires to be documented and unit
tested. Each weight below is stated with its reasoning rather than tuned until
the output looked plausible.
"""

from __future__ import annotations

from datetime import datetime, timedelta
from typing import Final

from .events import AqiCategory, CongestionLevel


# --- Air quality --------------------------------------------------------------

# CPCB breakpoints, which is the scale the seeded Indian cities report on.
# Upper bound of each band, inclusive.
_AQI_BANDS: Final[tuple[tuple[int, AqiCategory], ...]] = (
    (50, AqiCategory.GOOD),
    (100, AqiCategory.SATISFACTORY),
    (200, AqiCategory.MODERATE),
    (300, AqiCategory.POOR),
    (400, AqiCategory.VERY_POOR),
)


def aqi_category(aqi: int) -> AqiCategory:
    """Band an AQI reading.

    Derived rather than trusted from the producer, so a feed cannot report
    `aqi=380` labelled `GOOD` and have both stored.
    """
    for upper, category in _AQI_BANDS:
        if aqi <= upper:
            return category
    return AqiCategory.SEVERE


# --- Traffic ------------------------------------------------------------------

# Occupancy is vehicles present over the zone's rated capacity. The thresholds
# are where journey time starts to degrade noticeably, not evenly spaced bands:
# the jump from 0.85 to 1.0 costs far more travel time than 0.3 to 0.45.
_CONGESTION_BANDS: Final[tuple[tuple[float, CongestionLevel], ...]] = (
    (0.55, CongestionLevel.NORMAL),
    (0.80, CongestionLevel.MODERATE),
    (1.00, CongestionLevel.HIGH),
)


def congestion_level(occupancy_ratio: float) -> CongestionLevel:
    for upper, level in _CONGESTION_BANDS:
        if occupancy_ratio <= upper:
            return level
    return CongestionLevel.CRITICAL


def congestion_from_speed_ratio(speed_ratio: float) -> CongestionLevel:
    """The same four bands, for a road described by speed instead of fullness.

    A real feed reports how fast a segment is moving against its own free flow,
    never how full it is — see V22. The band still has to mean one thing across
    both, or "MODERATE" would describe two different roads depending on which
    feed happened to cover the zone, and the map legend would be a lie.

    So the boundaries are not chosen here. Each occupancy boundary is put
    through `speed_from_occupancy`, the relationship the platform already owns,
    and the speed it implies becomes the boundary:

        occupancy 0.55  ->  0.959 of free flow   NORMAL below
        occupancy 0.80  ->  0.782 of free flow   MODERATE below
        occupancy 1.00  ->  0.541 of free flow   HIGH below, CRITICAL under

    Banding is where that relationship is safe to use. The objection recorded in
    V22 is to deriving a *continuous* occupancy from speed, because BPR is so
    steep near free flow that one km/h of TomTom's whole-number rounding moves
    the result by half the scale. Four coarse bands do not carry that error:
    the worst it can do is put a road one band off at a boundary, which is what
    a boundary does to every measurement near it.
    """
    for occupancy_upper, level in _CONGESTION_BANDS:
        # Higher occupancy is slower, so the comparison flips: a road stays in
        # this band while it is *faster* than the speed the boundary implies.
        if speed_ratio >= _speed_ratio_at(occupancy_upper):
            return level
    return CongestionLevel.CRITICAL


def _speed_ratio_at(occupancy_ratio: float) -> float:
    """The fraction of free flow that `occupancy_ratio` implies, under BPR.

    Free-flow speed cancels, so this needs no road: it is `speed_from_occupancy`
    divided by the free flow it was given.
    """
    return 1.0 / (1.0 + _BPR_ALPHA * (occupancy_ratio**_BPR_BETA))


# Bureau of Public Roads coefficients. The textbook values (0.15, 4) are
# calibrated for uninterrupted highway flow, where travel time degrades only
# ~15% at capacity. Urban arterials with signals and turning conflicts degrade
# far more sharply, so these are the higher values used for signalised urban
# links. The difference is not cosmetic: with the highway constants a zone at
# 83% occupancy still reports ~45 km/h, which would put "HIGH congestion" and
# near-free-flow speed on the same tile and make the dashboard contradict itself.
_BPR_ALPHA: Final = 0.85
_BPR_BETA: Final = 5.0


def speed_from_occupancy(
    occupancy_ratio: float,
    free_flow_kph: float,
    jam_kph: float,
) -> float:
    """Speed implied by how full the road is.

    The BPR travel-time function, inverted to a speed: journey time rises as
    `1 + alpha*(v/c)^beta`, so speed holds up while there is slack and then
    falls away sharply near and above capacity. A linear model would understate
    the last 20% of loading, which is exactly the range the platform exists to
    warn about.

    The result is floored at the jam speed — traffic in gridlock still creeps —
    and capped at free flow, since an empty road does not exceed it.

    Roughly, with the default 48/8 km/h band:
        occupancy 0.40 → 46 km/h   (NORMAL)
        occupancy 0.70 → 41 km/h   (MODERATE)
        occupancy 0.90 → 30 km/h   (HIGH)
        occupancy 1.20 → 15 km/h   (CRITICAL)
    """
    if free_flow_kph <= jam_kph:
        raise ValueError("free_flow_kph must exceed jam_kph")
    load = max(0.0, occupancy_ratio)
    travel_time_factor = 1.0 + _BPR_ALPHA * (load**_BPR_BETA)
    speed = free_flow_kph / travel_time_factor
    return max(jam_kph, min(free_flow_kph, speed))


# --- Composite risk -----------------------------------------------------------

# Weights sum to 1.0. Congestion dominates because it is both the most
# frequently actionable signal and the one the other three tend to amplify.
_W_CONGESTION: Final = 0.40
_W_AIR_QUALITY: Final = 0.20
_W_INCIDENTS: Final = 0.25
_W_WEATHER: Final = 0.15

# An incident count saturates: the fourth simultaneous incident in one zone does
# not add as much risk as the first, because the zone is already degraded.
_INCIDENT_SATURATION: Final = 4.0

# Rain intensity at which the weather contribution is considered maximal.
# 25 mm/h is heavy rain by IMD classification.
_RAIN_SATURATION_MM_H: Final = 25.0


def _clamp01(value: float) -> float:
    return max(0.0, min(1.0, value))


#: Occupancy at which the congestion contribution is already maximal.
_OCCUPANCY_SATURATION: Final = 1.25

#: The speed ratio that _OCCUPANCY_SATURATION implies, through the same BPR
#: relationship `speed_from_occupancy` uses: 1 / (1 + alpha * 1.25^beta).
#:
#: Derived rather than chosen, so the two congestion inputs agree about where
#: the scale ends. A road at 28% of its free-flow speed and a road at 1.25x
#: capacity both score a full congestion contribution, and an unimpeded road
#: scores none either way.
#:
#: Between those endpoints the two disagree, and deliberately so. Occupancy maps
#: linearly to risk; propagating BPR's curve into the speed branch instead would
#: import its behaviour near free flow, where one km/h of TomTom's whole-number
#: rounding moves derived occupancy by half the scale. That sensitivity is
#: tolerable in a generator that owns both ends of the relationship and is not
#: tolerable in a risk score read off a dashboard. See V22.
_SPEED_RATIO_SATURATION: Final = _speed_ratio_at(_OCCUPANCY_SATURATION)


def risk_score(
    *,
    occupancy_ratio: float | None,
    aqi: int | None,
    active_incidents: int,
    precipitation_mm_h: float | None,
    speed_ratio: float | None = None,
) -> float | None:
    """Composite 0–100 risk for one zone-window.

    Returns None when no contributing signal is available. A zone with no data
    is not low risk — it is unknown, and reporting 0 would be a measurement the
    platform never made.

    Missing components are excluded and the remaining weights renormalised, so a
    zone with traffic but no air-quality feed still scores on what it has rather
    than being penalised for the gap.

    Congestion arrives as one of two measurements and never both. A generated
    window states `occupancy_ratio`, how full the road is; a window carrying a
    real feed states `speed_ratio`, how fast it is moving against its own free
    flow. Nothing counts vehicles on a road TomTom describes, so occupancy is
    absent there rather than estimated — see V22. Occupancy wins if both are
    somehow present, because it is the platform's native scale and a row holding
    both is a writer's bug worth surfacing as an inconsistency rather than
    silently averaging away.
    """
    components: list[tuple[float, float]] = []  # (weight, normalised 0-1)

    if occupancy_ratio is not None:
        # 1.25x capacity is treated as fully saturated risk; beyond that the
        # score is already at its ceiling and further loading changes nothing.
        components.append((_W_CONGESTION, _clamp01(occupancy_ratio / _OCCUPANCY_SATURATION)))
    elif speed_ratio is not None:
        # Delay against free flow, scaled so that the saturation point matches
        # the occupancy branch's. At or above free flow there is no congestion
        # to report; at or below the saturation ratio the contribution is full.
        delay = 1.0 - _clamp01(speed_ratio)
        components.append((_W_CONGESTION,
                           _clamp01(delay / (1.0 - _SPEED_RATIO_SATURATION))))

    if aqi is not None:
        # 400 is the top of VERY_POOR; SEVERE saturates the contribution.
        components.append((_W_AIR_QUALITY, _clamp01(aqi / 400.0)))

    # Incidents always contribute: zero incidents is a real measurement of low
    # risk, unlike a missing feed.
    components.append((_W_INCIDENTS, _clamp01(active_incidents / _INCIDENT_SATURATION)))

    if precipitation_mm_h is not None:
        components.append((_W_WEATHER, _clamp01(precipitation_mm_h / _RAIN_SATURATION_MM_H)))

    total_weight = sum(weight for weight, _ in components)
    if total_weight == 0:
        return None

    weighted = sum(weight * value for weight, value in components)
    return round(100.0 * weighted / total_weight, 2)


# Risk bands reuse the four PRD §9 states so one colour means one thing across
# congestion, air quality and composite risk.
_RISK_BANDS: Final[tuple[tuple[float, CongestionLevel], ...]] = (
    (25.0, CongestionLevel.NORMAL),
    (50.0, CongestionLevel.MODERATE),
    (75.0, CongestionLevel.HIGH),
)


def risk_level(score: float | None) -> str | None:
    if score is None:
        return None
    for upper, level in _RISK_BANDS:
        if score <= upper:
            return str(level)
    return str(CongestionLevel.CRITICAL)


# --- Windowing ----------------------------------------------------------------


def window_start(moment: datetime, window: timedelta) -> datetime:
    """Floor a timestamp to its tumbling window.

    Computed from the epoch rather than from the first event seen, so every
    producer, every replay and both execution paths agree on where a window
    begins. Anchoring to first-seen would make window boundaries depend on
    arrival order.
    """
    if moment.tzinfo is None:
        raise ValueError("refusing to window a naive datetime")
    seconds = int(window.total_seconds())
    if seconds <= 0:
        raise ValueError("window must be positive")
    epoch_seconds = int(moment.timestamp())
    floored = epoch_seconds - (epoch_seconds % seconds)
    return datetime.fromtimestamp(floored, tz=moment.tzinfo)
