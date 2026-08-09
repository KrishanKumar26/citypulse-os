"""Modelled weather for every city, from Open-Meteo.

    python -m ingest.weather

The second signal to stop being invented, and the same argument as the air:
a numerical weather model assimilates observations and solves for a field, so
it produces a reading for any coordinate without an instrument standing there.
That is MODELLED — ranked below a measurement and far above a generator, which
tracks nothing at all.

Weather is stored per city rather than per zone, because it is measured per
city: a metro's zones share conditions closely enough that a per-zone
temperature would be false precision. `weather_events.city_id` says so, and this
follows it — one request per city centre, not per zone.

**The condition scale is this platform's, not WMO's.** Open-Meteo reports a WMO
code; `zone_metrics.weather_condition` holds nine labels chosen for Indian
metros. The mapping is stated below and is deliberately incomplete: the snow
codes have no honest target in a scale with no snow in it, and a reading that
cannot be labelled is dropped rather than filed under the nearest-looking
label. Dropping loses a row; mislabelling puts a wrong word on a dashboard.

**Nothing is estimated.** Visibility is reported by the model or left null. The
platform's HAZE label has no WMO equivalent and is never produced here — it
remains something only the generator emits, which is the truth about it.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import dict_row, tuple_row  # noqa: E402

from common.db import execute_batched  # noqa: E402
from ingest import air_store  # noqa: E402
from pipeline.provenance import MODELLED, overlay_weather  # noqa: E402

ENDPOINT = "https://api.open-meteo.com/v1/forecast"

SOURCE_CODE = "open-meteo-weather"

CURRENT_FIELDS = (
    "temperature_2m",
    "relative_humidity_2m",
    "precipitation",
    "wind_speed_10m",
    "visibility",
    "weather_code",
)

#: WMO present-weather code against this platform's condition scale.
#:
#: Grouped rather than enumerated one-to-one because the platform's scale is
#: coarser: it distinguishes light rain from rain from heavy rain, and does not
#: distinguish drizzle from freezing drizzle. Codes absent here have no honest
#: target — 71 to 86 are snow, which a scale built for Indian metros has no word
#: for — and a reading carrying one is dropped.
WMO_CONDITION: dict[int, str] = {
    0: "CLEAR", 1: "CLEAR",
    2: "CLOUDY",
    3: "OVERCAST",
    45: "FOG", 48: "FOG",
    51: "LIGHT_RAIN", 53: "LIGHT_RAIN", 55: "LIGHT_RAIN",
    56: "LIGHT_RAIN", 57: "LIGHT_RAIN",
    61: "LIGHT_RAIN",
    63: "RAIN", 66: "RAIN", 67: "RAIN",
    65: "HEAVY_RAIN",
    80: "RAIN", 81: "RAIN",
    82: "HEAVY_RAIN",
    95: "THUNDERSTORM", 96: "THUNDERSTORM", 99: "THUNDERSTORM",
}

#: A reading this far behind the clock is not describing the present.
MAX_READING_AGE = timedelta(hours=3)

#: Curated windows this run may repoint — see ingest.waqi.
OVERLAY_WINDOW = timedelta(hours=6)

#: Cities per request. The API takes a comma-separated list; this bounds the URL.
BATCH = 30


@dataclass(frozen=True)
class City:
    id: int
    slug: str
    latitude: float
    longitude: float


@dataclass(frozen=True)
class Reading:
    city_id: int
    event_time: datetime
    temperature_c: float
    humidity_pct: float
    precipitation_mm_h: float
    wind_speed_kph: float
    visibility_km: float | None
    condition: str


def active_cities(connection: psycopg.Connection) -> list[City]:
    with connection.cursor(row_factory=tuple_row) as cursor:
        cursor.execute("""
            SELECT id, slug, center_latitude, center_longitude
              FROM cities
             WHERE deleted_at IS NULL
             ORDER BY slug
        """)
        return [City(int(i), str(slug), float(lat), float(lon))
                for i, slug, lat, lon in cursor.fetchall()]


def _get(latitudes: list[float], longitudes: list[float], *,
         attempts: int = 3, timeout: float = 45.0) -> list[dict]:
    query = urllib.parse.urlencode({
        "latitude": ",".join(f"{v:.4f}" for v in latitudes),
        "longitude": ",".join(f"{v:.4f}" for v in longitudes),
        "current": ",".join(CURRENT_FIELDS),
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


def _number(current: dict, key: str) -> float | None:
    raw = current.get(key)
    if raw is None:
        return None
    try:
        return float(raw)
    except (TypeError, ValueError):
        return None


def read(city: City, block: dict, now: datetime) -> Reading | None:
    """One city's current conditions, or nothing if they cannot be stated.

    Returns None rather than a partial row. `weather_events` declares
    temperature, humidity, precipitation, wind and condition NOT NULL, and
    inventing any of them to satisfy the constraint would be the platform
    filling its own gap.
    """
    current = block.get("current")
    if not isinstance(current, dict):
        return None

    raw_time = current.get("time")
    if not isinstance(raw_time, str):
        return None
    try:
        moment = datetime.fromisoformat(raw_time).replace(tzinfo=timezone.utc)
    except ValueError:
        return None
    if now - moment > MAX_READING_AGE:
        return None

    code = current.get("weather_code")
    condition = WMO_CONDITION.get(int(code)) if isinstance(code, (int, float)) else None
    if condition is None:
        # Snow, or a code this scale has no word for. Dropped rather than filed
        # under whichever label looks closest.
        return None

    temperature = _number(current, "temperature_2m")
    humidity = _number(current, "relative_humidity_2m")
    precipitation = _number(current, "precipitation")
    wind = _number(current, "wind_speed_10m")
    if None in (temperature, humidity, precipitation, wind):
        return None

    visibility_m = _number(current, "visibility")

    return Reading(
        city_id=city.id,
        event_time=moment,
        temperature_c=round(temperature, 2),
        humidity_pct=round(min(max(humidity, 0.0), 100.0), 2),
        # The API reports the millimetres that fell in the interval, and the
        # column is a rate. The interval is a quarter hour, so the hourly rate
        # is four times it — stated here rather than left as a unit mismatch
        # nobody would see until a rain figure looked wrong.
        precipitation_mm_h=round(precipitation * 4, 2),
        wind_speed_kph=round(wind, 2),
        visibility_km=None if visibility_m is None else round(visibility_m / 1000, 2),
        condition=condition,
    )


def main() -> int:
    dsn = os.environ.get("CITYPULSE_PG_DSN")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1

    now = datetime.now(timezone.utc)

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        source = air_store.source_id(connection, SOURCE_CODE)
        if source is None:
            print(f"No data source '{SOURCE_CODE}'. Migration V21 seeds it.")
            return 1

        cities = active_cities(connection)
        if not cities:
            print("No cities to fetch weather for.")
            return 1

        readings: list[Reading] = []
        skipped = 0

        for start in range(0, len(cities), BATCH):
            chunk = cities[start:start + BATCH]
            blocks = _get([c.latitude for c in chunk], [c.longitude for c in chunk])
            if len(blocks) != len(chunk):
                print(f"  asked for {len(chunk)} cities and got {len(blocks)}; "
                      f"skipping the batch rather than pairing them by guess")
                continue

            for city, block in zip(chunk, blocks):
                reading = read(city, block, now)
                if reading is None:
                    skipped += 1
                    continue
                readings.append(reading)

        print(f"  {len(cities)} cities asked about")
        if skipped:
            print(f"  {skipped} skipped: stale, incomplete, or a condition this "
                  f"scale has no word for")

        written = 0
        overlaid: dict[str, int] = {}
        if readings:
            execute_batched(connection, """
                INSERT INTO weather_events
                    (event_id, city_id, source_id, event_time, temperature_c,
                     humidity_pct, precipitation_mm_h, wind_speed_kph,
                     visibility_km, condition, demo_data)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (event_id) DO NOTHING
            """, [(
                str(uuid.uuid4()), r.city_id, source, r.event_time, r.temperature_c,
                r.humidity_pct, r.precipitation_mm_h, r.wind_speed_kph,
                r.visibility_km, r.condition, False,
            ) for r in readings])
            written = len(readings)

            air_store.mark_delivered(connection, source)
            overlaid = overlay_weather(connection, since=now - OVERLAY_WINDOW)

    print(f"  {written} modelled readings written (demo_data = false)")
    print(f"  {overlaid.get(MODELLED, 0)} curated windows now report modelled weather")
    if not written:
        print("  Nothing was written, so the source stays PAUSED.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
