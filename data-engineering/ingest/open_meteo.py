"""Modelled air quality for every zone, from Copernicus CAMS via Open-Meteo.

    python -m ingest.open_meteo

No key, no registration, no quota to negotiate. That is why this exists next to
the station feed rather than instead of it: WAQI covers the zones a government
instrument happens to sit in, which here is a small minority of them, and every
other zone would otherwise keep an AQI this repository invented.

CAMS is not an instrument. It assimilates satellite retrievals and ground
stations into a physical model of the atmosphere and then solves for a
concentration field, so it produces a number for any coordinate on earth,
including the ones no instrument is near. That number tracks what the air
actually did — it puts Delhi at 192 while Bengaluru sits at 29 on the same
afternoon — and it is still not a measurement, because nothing measured it.
Hence MODELLED: a third state, ranked below MEASURED and above the generator,
rather than a claim in either direction.

**CPCB's index, on CPCB's averaging periods.** Open-Meteo publishes
concentrations in µg/m³, which is what CPCB's breakpoints are stated in, so the
platform's own `ingest.cpcb_aqi` computes the index with no scale conversion at
all. The averaging matters as much as the units: CPCB's sub-indices are defined
on 24-hour means for the particulates, NO2 and SO2, and 8-hour means for CO and
ozone. Passing a single hour's concentration through a table built for a daily
mean would report every afternoon peak as a day of it, so the hourly series is
fetched and each pollutant is averaged over the period its own breakpoints
assume.

**Gaps stay gaps.** A pollutant CAMS did not return for an hour is left out of
that hour's mean rather than filled, and a pollutant missing entirely never
reaches the index. If too few remain, `cpcb_aqi.compute` refuses and the zone
keeps the generated AQI it already had.

Attribution is fixed and known, so migration V19 writes it: Open-Meteo under
CC BY 4.0, and the Copernicus Atmosphere Monitoring Service behind it.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import dict_row  # noqa: E402

from ingest import air_store  # noqa: E402
from ingest.cpcb_aqi import compute  # noqa: E402
from pipeline.provenance import MEASURED, MODELLED, overlay  # noqa: E402

ENDPOINT = "https://air-quality-api.open-meteo.com/v1/air-quality"

SOURCE_CODE = "open-meteo-cams"

# Open-Meteo's variable names against the names CPCB's breakpoints use, with
# the averaging period CPCB defines each sub-index on. Hours, not taste: the
# 2014 expert-group table states 24-hour means for the particulates and the two
# acid gases, 8-hour for carbon monoxide and ozone.
POLLUTANTS: dict[str, tuple[str, int]] = {
    "pm2_5": ("PM2.5", 24),
    "pm10": ("PM10", 24),
    "nitrogen_dioxide": ("NO2", 24),
    "sulphur_dioxide": ("SO2", 24),
    "ozone": ("OZONE", 8),
    "carbon_monoxide": ("CO", 8),
}

# CPCB states carbon monoxide in mg/m³ and everything else in µg/m³; Open-Meteo
# states all six in µg/m³.
UNIT_SCALE: dict[str, float] = {"CO": 0.001}

# Locations per request. The API takes a comma-separated list and answers with
# one block each; this only bounds the URL length.
BATCH = 30

# Hours of history to ask for. Enough to fill a 24-hour mean ending at the most
# recent hour CAMS has published, with room for the publication lag.
PAST_DAYS = 2

# An hour of CAMS this far behind the clock is not describing the present.
MAX_READING_AGE = timedelta(hours=4)

# Curated windows this run may repoint — see ingest.waqi.
OVERLAY_WINDOW = timedelta(hours=6)


def _get(latitudes: list[float], longitudes: list[float], *,
         attempts: int = 3, timeout: float = 60.0) -> list[dict]:
    query = urllib.parse.urlencode({
        "latitude": ",".join(f"{v:.4f}" for v in latitudes),
        "longitude": ",".join(f"{v:.4f}" for v in longitudes),
        "hourly": ",".join(POLLUTANTS),
        "past_days": PAST_DAYS,
        "forecast_days": 1,
        "timezone": "UTC",
    })
    request = urllib.request.Request(f"{ENDPOINT}?{query}",
                                     headers={"Accept": "application/json"})

    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = json.load(response)
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last = exc
            if attempt < attempts:
                time.sleep(3 * attempt)
            continue
        # One location comes back as an object, several as an array.
        return payload if isinstance(payload, list) else [payload]

    raise RuntimeError(f"Open-Meteo unreachable after {attempts} attempts: {last}")


def _hours(block: dict) -> list[datetime]:
    """The hourly grid, in UTC. Requested with timezone=UTC, so it is naive."""
    out = []
    for raw in (block.get("hourly") or {}).get("time") or []:
        try:
            out.append(datetime.fromisoformat(raw).replace(tzinfo=timezone.utc))
        except (TypeError, ValueError):
            out.append(None)
    return out


def latest_hour(block: dict, grid: list[datetime], now: datetime) -> int | None:
    """Index of the most recent hour that is in the past and has any value.

    The response runs into the forecast, and the trailing hours of a forecast
    are still nulls at the moment CAMS has not published them. Taking the last
    row regardless would average a window that is mostly empty.
    """
    hourly = block.get("hourly") or {}
    for index in range(len(grid) - 1, -1, -1):
        moment = grid[index]
        if moment is None or moment > now:
            continue
        if any(_value(hourly, name, index) is not None for name in POLLUTANTS):
            return index
    return None


def _value(hourly: dict, name: str, index: int) -> float | None:
    series = hourly.get(name)
    if not isinstance(series, list) or index >= len(series):
        return None
    raw = series[index]
    if raw is None:
        return None
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


def concentrations(block: dict, end: int) -> dict[str, float]:
    """Each pollutant averaged over the period its CPCB sub-index is defined on.

    Hours CAMS did not publish are absent from the mean rather than zero, and a
    pollutant with nothing in its window is absent from the result: a zero here
    would read as clean air and would satisfy CPCB's minimum-pollutant rule on
    the strength of a reading that does not exist.
    """
    hourly = block.get("hourly") or {}
    out: dict[str, float] = {}

    for name, (cpcb_name, period) in POLLUTANTS.items():
        start = max(0, end - period + 1)
        values = [v for v in (_value(hourly, name, i) for i in range(start, end + 1))
                  if v is not None]
        if not values:
            continue
        mean = sum(values) / len(values)
        out[cpcb_name] = round(mean * UNIT_SCALE.get(cpcb_name, 1.0), 3)

    return out


def main() -> int:
    dsn = os.environ.get("CITYPULSE_PG_DSN")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1

    now = datetime.now(timezone.utc)

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        source = air_store.source_id(connection, SOURCE_CODE)
        if source is None:
            print(f"No data source '{SOURCE_CODE}'. Migration V19 seeds it.")
            return 1

        zones = air_store.active_zones(connection)
        if not zones:
            print("No active zones to model air for.")
            return 1

        readings: list[air_store.Reading] = []
        stale = no_index = 0

        for start in range(0, len(zones), BATCH):
            chunk = zones[start:start + BATCH]
            blocks = _get([z.latitude for z in chunk], [z.longitude for z in chunk])
            if len(blocks) != len(chunk):
                print(f"  asked for {len(chunk)} locations and got {len(blocks)}; "
                      f"skipping the batch rather than pairing them by guess")
                continue

            for zone, block in zip(chunk, blocks):
                grid = _hours(block)
                index = latest_hour(block, grid, now)
                if index is None:
                    stale += 1
                    continue

                moment = grid[index]
                if now - moment > MAX_READING_AGE:
                    stale += 1
                    continue

                measured = concentrations(block, index)
                result = compute(measured)
                if result is None:
                    no_index += 1
                    continue

                readings.append(air_store.Reading(
                    zone_id=zone.id,
                    event_time=moment,
                    aqi=result.aqi,
                    category=result.category,
                    # These are the concentrations the index was computed from,
                    # in the units the columns hold. Unlike the station feed,
                    # CAMS states them directly, so recording them republishes
                    # what the provider said rather than a reconstruction.
                    pm25=measured.get("PM2.5"),
                    pm10=measured.get("PM10"),
                    no2=measured.get("NO2"),
                    o3=measured.get("OZONE"),
                    co=measured.get("CO"),
                ))

        print(f"  {len(zones)} zones asked about")
        if stale:
            print(f"  {stale} skipped: no CAMS hour recent enough")
        if no_index:
            print(f"  {no_index} skipped: fewer pollutants than CPCB publishes an index from")

        written = air_store.write(connection, source, readings)
        overlaid: dict[str, int] = {}
        if written:
            air_store.mark_delivered(connection, source)
            overlaid = overlay(connection, since=now - OVERLAY_WINDOW)

    print(f"  {written} modelled readings written (demo_data = false)")
    print(f"  {overlaid.get(MODELLED, 0)} curated windows now report modelled air")
    if overlaid.get(MEASURED):
        print(f"  {overlaid[MEASURED]} kept measured air, which outranks a model")
    if not written:
        print("  Nothing was written, so the source stays PAUSED.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
