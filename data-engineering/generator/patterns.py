"""Urban demand curves used to shape synthetic events.

Pure functions of (local time, zone character) → multiplier. Kept separate from
the generators so the shape of a city's day can be tested directly, without
producing an event or touching a database.

The curves model Indian metro weekday patterns, which is what the seeded demo
cities are (docs/DEVELOPMENT_PLAN.md, Phase 0 assumption 4). They are
deliberately simple and legible: a synthetic feed whose behaviour cannot be
explained is worse than one that is obviously approximate.
"""

from __future__ import annotations

import math
from datetime import datetime
from typing import Final


# Zone character multipliers on baseline traffic demand. A transit hub sees far
# more vehicles per unit area than a residential street; without this every zone
# would breathe identically and the map would carry no information.
ZONE_TYPE_DEMAND: Final[dict[str, float]] = {
    "TRANSIT_HUB": 1.35,
    "COMMERCIAL": 1.20,
    "AIRPORT": 1.10,
    "MIXED": 1.00,
    "EDUCATIONAL": 0.95,
    "INDUSTRIAL": 0.90,
    "RESIDENTIAL": 0.80,
    "RECREATIONAL": 0.70,
}

# How sharply each zone type peaks. Offices empty in a two-hour window;
# residential demand is spread across the day.
ZONE_TYPE_PEAKINESS: Final[dict[str, float]] = {
    "COMMERCIAL": 1.25,
    "TRANSIT_HUB": 1.20,
    "EDUCATIONAL": 1.15,
    "INDUSTRIAL": 1.00,
    "MIXED": 1.00,
    "AIRPORT": 0.75,
    "RESIDENTIAL": 0.85,
    "RECREATIONAL": 0.70,
}


def _gaussian_peak(hour: float, centre: float, width: float) -> float:
    """A smooth bump centred on an hour, wrapping across midnight."""
    delta = abs(hour - centre)
    delta = min(delta, 24.0 - delta)
    return math.exp(-0.5 * (delta / width) ** 2)


def traffic_demand_multiplier(
    local_time: datetime,
    *,
    zone_type: str,
    morning_peak_hour: float = 9.0,
    evening_peak_hour: float = 18.0,
    peak_multiplier: float = 2.4,
    night_multiplier: float = 0.18,
    weekend_multiplier: float = 0.65,
) -> float:
    """Demand relative to a zone's average, for a given local time.

    Two commute peaks over a low overnight floor. The evening peak is modelled
    slightly wider than the morning one because departure times are less
    synchronised than arrival times — people leave when their work is done, but
    arrive when it starts.
    """
    hour = local_time.hour + local_time.minute / 60.0

    peakiness = ZONE_TYPE_PEAKINESS.get(zone_type, 1.0)
    morning = _gaussian_peak(hour, morning_peak_hour, 1.5)
    evening = _gaussian_peak(hour, evening_peak_hour, 1.9)
    peak_component = max(morning, evening) * peakiness

    # Daytime baseline between the peaks, tapering to the overnight floor.
    daytime = _gaussian_peak(hour, 13.0, 4.5)
    baseline = night_multiplier + (0.55 - night_multiplier) * daytime

    demand = baseline + (peak_multiplier - baseline) * peak_component

    # Saturday and Sunday: commute peaks largely disappear.
    if local_time.weekday() >= 5:
        demand *= weekend_multiplier
        # Weekend traffic is midday-heavy rather than peaked.
        demand += 0.25 * _gaussian_peak(hour, 14.0, 3.5)

    demand *= ZONE_TYPE_DEMAND.get(zone_type, 1.0)
    return max(night_multiplier * 0.5, demand)


def temperature_c(
    local_time: datetime,
    *,
    base_temperature_c: float = 27.0,
    diurnal_swing_c: float = 7.0,
) -> float:
    """Diurnal temperature curve.

    Minimum near 05:00, maximum near 15:00 — the lag behind solar noon that
    thermal mass produces. A curve peaking at 12:00 would be wrong in a way
    anyone reading the chart would notice.
    """
    hour = local_time.hour + local_time.minute / 60.0
    phase = (hour - 5.0) / 24.0 * 2.0 * math.pi
    return base_temperature_c + (diurnal_swing_c / 2.0) * -math.cos(phase)


def monsoon_intensity(local_time: datetime) -> float:
    """Seasonal rain likelihood multiplier, 0.2 to 1.0.

    Peaks July–August, matching the Indian southwest monsoon. Rain is the
    signal the correlation engine leans on most, so it needs a season rather
    than a uniform random chance across the year.
    """
    month = local_time.month + (local_time.day - 1) / 31.0
    peak = _gaussian_peak((month / 12.0) * 24.0, (7.5 / 12.0) * 24.0, 3.0)
    return 0.2 + 0.8 * peak


def aqi_baseline(
    local_time: datetime,
    *,
    base_aqi: float,
    traffic_multiplier: float,
    traffic_coupling: float,
    industrial_penalty: float,
    is_industrial: bool,
) -> float:
    """Air quality driven by traffic, with a winter inversion effect.

    Two things dominate urban AQI in these cities: how much is being burned
    right now, and whether the air is trapped. Cool, still winter nights trap
    it, which is why AQI peaks in the early morning rather than at rush hour.
    """
    traffic_component = 1.0 + traffic_coupling * (traffic_multiplier - 1.0)

    hour = local_time.hour + local_time.minute / 60.0
    # Nocturnal inversion: strongest just before dawn.
    inversion = 1.0 + 0.35 * _gaussian_peak(hour, 6.0, 2.5)

    # Winter months trap particulates far more than the monsoon does.
    winter = 1.0 + 0.45 * _gaussian_peak((local_time.month / 12.0) * 24.0, (1.0 / 12.0) * 24.0, 3.5)

    value = base_aqi * traffic_component * inversion * winter
    if is_industrial:
        value += industrial_penalty
    return value
