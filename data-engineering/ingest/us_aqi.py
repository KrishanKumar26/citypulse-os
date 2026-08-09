"""Recover pollutant concentrations from US-EPA sub-indices.

WAQI publishes an index, not a concentration. Its `iaqi` block holds one
US-EPA sub-index per pollutant — `iaqi.pm25.v = 42` means "PM2.5 scored 42 on
the US scale", not "42 µg/m³". The station's headline `aqi` is the maximum of
those sub-indices, which is how the two can be checked against each other.

This platform's AQI is CPCB's. Its bands are the ones `zone_metrics.aqi_category`
stores — GOOD through SEVERE — and they are not the US bands: a US AQI of 192 is
*Unhealthy*, while CPCB puts 192 in *MODERATE*. Writing a US number into a
CPCB-banded column would produce a value that is wrong by a whole category and
carries no sign of it, which is the precise failure this codebase exists to
avoid.

So the index is taken back to what it was computed from, and the CPCB index is
computed from that instead. Both steps are defined arithmetic:

**Which US table.** aqicn.org/scale/ states the scale is "the US-EPA 2016
standard", so the pre-2024 PM2.5 breakpoints apply — 0–12.0 µg/m³ for 0–50, not
the 0–9.0 of the 2024 revision. Inverting against the wrong table would shift
every particulate reading; it is pinned here, with the source, rather than
assumed.

**How exact the inverse is.** EPA sub-indices are integers, so an index maps
back to a band of concentrations one index-step wide rather than to a point. The
midpoint of that band is returned. The residual is the step width: about
0.4 µg/m³ for PM2.5 near index 100, about 1.9 µg/m³ near index 200 — far below
the disagreement between two instruments measuring the same air.

**Units.** EPA states the gases in ppb (CO in ppm) and CPCB in µg/m³ (CO in
mg/m³). The conversion is not an estimate: EPA's gaseous standards are defined
at 25 °C and 1 atm, where a mole occupies 24.45 litres, so µg/m³ = ppb × M/24.45
is exact at the conditions the standard itself specifies. The particulates need
no conversion — both scales state them in µg/m³.

What is not recovered is not invented. A pollutant the station did not report
has no sub-index and gets no concentration, and `ingest.cpcb_aqi.compute` then
applies CPCB's own rule about how many are needed before there is an index at
all.
"""

from __future__ import annotations

# (concentration_low, concentration_high, index_low, index_high), US-EPA 2016.
#
# Particulates in µg/m³; O3, NO2 and SO2 in ppb; CO in ppm. The upper
# concentration of each band is EPA's truncated value (35.4, not 35.5), so the
# bands abut without overlapping once the truncation EPA applies is undone.
_BREAKPOINTS: dict[str, tuple[tuple[float, float, int, int], ...]] = {
    "PM2.5": (
        (0.0, 12.0, 0, 50), (12.1, 35.4, 51, 100), (35.5, 55.4, 101, 150),
        (55.5, 150.4, 151, 200), (150.5, 250.4, 201, 300),
        (250.5, 350.4, 301, 400), (350.5, 500.4, 401, 500),
    ),
    "PM10": (
        (0, 54, 0, 50), (55, 154, 51, 100), (155, 254, 101, 150),
        (255, 354, 151, 200), (355, 424, 201, 300),
        (425, 504, 301, 400), (505, 604, 401, 500),
    ),
    "OZONE": (  # ppb, 8-hour
        (0, 54, 0, 50), (55, 70, 51, 100), (71, 85, 101, 150),
        (86, 105, 151, 200), (106, 200, 201, 300),
    ),
    "NO2": (  # ppb, 1-hour
        (0, 53, 0, 50), (54, 100, 51, 100), (101, 360, 101, 150),
        (361, 649, 151, 200), (650, 1249, 201, 300),
        (1250, 1649, 301, 400), (1650, 2049, 401, 500),
    ),
    "SO2": (  # ppb, 1-hour
        (0, 35, 0, 50), (36, 75, 51, 100), (76, 185, 101, 150),
        (186, 304, 151, 200), (305, 604, 201, 300),
        (605, 804, 301, 400), (805, 1004, 401, 500),
    ),
    "CO": (  # ppm, 8-hour
        (0.0, 4.4, 0, 50), (4.5, 9.4, 51, 100), (9.5, 12.4, 101, 150),
        (12.5, 15.4, 151, 200), (15.5, 30.4, 201, 300),
        (30.5, 40.4, 301, 400), (40.5, 50.4, 401, 500),
    ),
}

# Molar volume of an ideal gas at EPA's reference conditions, 25 °C and 1 atm.
_MOLAR_VOLUME_L = 24.45

# ppb → µg/m³ for the gases CPCB states in µg/m³, and ppm → mg/m³ for CO, which
# CPCB states in mg/m³. Both are M / 24.45 — the units differ by a factor of a
# thousand on each side, which cancels.
_TO_CPCB_UNITS: dict[str, float] = {
    "OZONE": 48.00 / _MOLAR_VOLUME_L,
    "NO2": 46.0055 / _MOLAR_VOLUME_L,
    "SO2": 64.066 / _MOLAR_VOLUME_L,
    "CO": 28.010 / _MOLAR_VOLUME_L,
}

# WAQI's `iaqi` keys against the names ingest.cpcb_aqi breakpoints use. The
# block also carries t, h, w, p — temperature, humidity, wind and pressure —
# which are weather, not pollutants, and are absent here deliberately: mapping
# one of them would put a wind speed through a pollutant's breakpoints and get
# an index out.
IAQI_POLLUTANTS: dict[str, str] = {
    "pm25": "PM2.5",
    "pm10": "PM10",
    "o3": "OZONE",
    "no2": "NO2",
    "so2": "SO2",
    "co": "CO",
}


def concentration_for(pollutant: str, index: float) -> float | None:
    """The concentration behind a US-EPA sub-index, in CPCB's units.

    Returns None when the pollutant has no US breakpoints, when the index is
    negative, or when it is above the top of the table. Off the top of the
    scale, EPA's own linear form stops being defined, and extrapolating it would
    manufacture a concentration from a number that only says "beyond the scale".
    """
    table = _BREAKPOINTS.get(pollutant.upper())
    if table is None or index < 0:
        return None

    for low_c, high_c, low_i, high_i in table:
        if low_i <= index <= high_i:
            span = high_i - low_i
            # The band the index came from is one step wide; its midpoint is the
            # best single answer, and the residual is half a step.
            offset = 0.5 if span == 0 else (index - low_i + 0.5) / (span + 1)
            concentration = low_c + (high_c - low_c) * offset
            return concentration * _TO_CPCB_UNITS.get(pollutant.upper(), 1.0)

    return None


def concentrations(iaqi: dict[str, dict]) -> dict[str, float]:
    """Every pollutant in a WAQI `iaqi` block, as a CPCB-units concentration.

    Non-pollutant entries and unreadable values are dropped rather than
    defaulted. A station that did not report SO2 must reach the index
    calculation with no SO2, so that CPCB's minimum-pollutant rule can see the
    gap and refuse; a zero there would read as clean air and satisfy the rule
    on the strength of a reading that does not exist.
    """
    out: dict[str, float] = {}
    for key, cpcb_name in IAQI_POLLUTANTS.items():
        entry = iaqi.get(key)
        if not isinstance(entry, dict):
            continue
        try:
            index = float(entry["v"])
        except (KeyError, TypeError, ValueError):
            continue
        value = concentration_for(cpcb_name, index)
        if value is not None:
            out[cpcb_name] = round(value, 3)
    return out
