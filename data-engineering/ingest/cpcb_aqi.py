"""CPCB's National Air Quality Index, computed from pollutant concentrations.

The data.gov.in feed publishes per-pollutant readings, not an index. Turning
those into an AQI is a defined calculation, not a judgement call, and it is
reproduced here rather than approximated:

  1. Each pollutant gets a sub-index by linear interpolation between the
     breakpoints CPCB publishes for it.
  2. The AQI is the **maximum** of the sub-indices, not the mean. One pollutant
     at a harmful level makes the air harmful regardless of how clean the others
     are, and averaging would let a single dangerous reading disappear behind
     five good ones.
  3. CPCB requires at least three pollutants, one of which must be PM2.5 or
     PM10, before an index may be published at all. Below that the station
     reports no AQI — which this module returns as None rather than computing
     something from what happened to be available.

Breakpoints are from CPCB's *National Air Quality Index: Report of the Expert
Group* (2014), the same table the official bulletins use. Averaging periods
differ by pollutant — 24-hour for the particulates, SO2, NO2, NH3 and Pb;
8-hour for CO and O3 — and the feed's `pollutant_avg` is used as supplied.

Nothing here estimates a missing pollutant. A station that did not report PM2.5
has not measured PM2.5, and inferring it from PM10 would produce a number with
the appearance of a measurement and none of the substance.
"""

from __future__ import annotations

from dataclasses import dataclass

# (concentration_low, concentration_high, index_low, index_high) per pollutant.
# Units: µg/m³ except CO, which CPCB specifies in mg/m³.
_BREAKPOINTS: dict[str, tuple[tuple[float, float, int, int], ...]] = {
    "PM2.5": (
        (0, 30, 0, 50), (30, 60, 51, 100), (60, 90, 101, 200),
        (90, 120, 201, 300), (120, 250, 301, 400), (250, 380, 401, 500),
    ),
    "PM10": (
        (0, 50, 0, 50), (50, 100, 51, 100), (100, 250, 101, 200),
        (250, 350, 201, 300), (350, 430, 301, 400), (430, 510, 401, 500),
    ),
    "NO2": (
        (0, 40, 0, 50), (40, 80, 51, 100), (80, 180, 101, 200),
        (180, 280, 201, 300), (280, 400, 301, 400), (400, 520, 401, 500),
    ),
    "SO2": (
        (0, 40, 0, 50), (40, 80, 51, 100), (80, 380, 101, 200),
        (380, 800, 201, 300), (800, 1600, 301, 400), (1600, 2400, 401, 500),
    ),
    "CO": (  # mg/m³, 8-hour
        (0, 1, 0, 50), (1, 2, 51, 100), (2, 10, 101, 200),
        (10, 17, 201, 300), (17, 34, 301, 400), (34, 51, 401, 500),
    ),
    "OZONE": (
        (0, 50, 0, 50), (50, 100, 51, 100), (100, 168, 101, 200),
        (168, 208, 201, 300), (208, 748, 301, 400), (748, 1000, 401, 500),
    ),
    "NH3": (
        (0, 200, 0, 50), (200, 400, 51, 100), (400, 800, 101, 200),
        (800, 1200, 201, 300), (1200, 1800, 301, 400), (1800, 2400, 401, 500),
    ),
}

# The two CPCB requires one of before an index may be published.
_PARTICULATES = ("PM2.5", "PM10")

# CPCB's own minimum. Fewer than three pollutants is not an index.
_MIN_POLLUTANTS = 3

# The bands the bulletins use, and the labels the platform already stores.
_CATEGORIES: tuple[tuple[int, str], ...] = (
    (50, "GOOD"),
    (100, "SATISFACTORY"),
    (200, "MODERATE"),
    (300, "POOR"),
    (400, "VERY_POOR"),
    (500, "SEVERE"),
)


@dataclass(frozen=True)
class AqiResult:
    """An index, and what it was computed from.

    `dominant` is the pollutant that produced the maximum. It is the reason the
    number is what it is, and a bulletin that gives an AQI without it tells a
    reader nothing about what to do.
    """

    aqi: int
    category: str
    dominant: str
    sub_indices: dict[str, int]


def sub_index(pollutant: str, concentration: float) -> int | None:
    """One pollutant's sub-index, or None if it has no CPCB breakpoints.

    Concentrations above the top breakpoint return 500 rather than extrapolating.
    The scale ends there; a reading beyond it is off the scale, and inventing 640
    would imply a precision the index does not define.
    """
    table = _BREAKPOINTS.get(pollutant.upper())
    if table is None or concentration < 0:
        return None

    for low_c, high_c, low_i, high_i in table:
        if low_c <= concentration <= high_c:
            span = high_c - low_c
            if span == 0:
                return low_i
            return round(low_i + (high_i - low_i) * (concentration - low_c) / span)

    return 500


def category_for(aqi: int) -> str:
    for upper, label in _CATEGORIES:
        if aqi <= upper:
            return label
    return "SEVERE"


def compute(concentrations: dict[str, float]) -> AqiResult | None:
    """The station's AQI, or None when CPCB's own rules say there is not one.

    Returns None when fewer than three pollutants reported, or when neither
    particulate did. That is a refusal, not a failure: publishing an index from
    two gases would meet no standard and would be indistinguishable, on the
    dashboard, from one that does.
    """
    indices: dict[str, int] = {}
    for pollutant, value in concentrations.items():
        index = sub_index(pollutant, value)
        if index is not None:
            indices[pollutant.upper()] = index

    if len(indices) < _MIN_POLLUTANTS:
        return None
    if not any(p in indices for p in _PARTICULATES):
        return None

    dominant = max(indices, key=lambda p: indices[p])
    aqi = indices[dominant]
    return AqiResult(aqi=aqi, category=category_for(aqi), dominant=dominant,
                     sub_indices=indices)
