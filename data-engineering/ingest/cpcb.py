"""Real air quality from CPCB, via data.gov.in.

    CPCB_API_KEY=... python -m ingest.cpcb

The first genuinely external feed the platform has. Everything else is generated
by design (PRD §43), and every generated row carries `demo_data = TRUE`. These
rows carry FALSE, and that single difference is the whole point of the module:
the dashboard can then say which of its numbers came from an instrument.

Three things this refuses to do, each of which would produce a number that looks
like a measurement and is not:

**It does not place a station in a zone it is not near.** CPCB stations are at
fixed coordinates; zones are areas with a centre. Assigning the nearest station
regardless of distance would attribute Anand Vihar's air to a suburb fifteen
kilometres away. A station beyond MAX_STATION_KM is not used at all, and the
distance that was accepted is recorded on every reading.

**It does not compute an index CPCB would not publish.** Fewer than three
pollutants, or no particulate among them, and the station reports nothing. See
`cpcb_aqi`.

**It does not fill gaps.** A pollutant a station did not report stays null. It is
not estimated from the others, and the AQI is computed from what was actually
measured.

Without CPCB_API_KEY the module exits without writing anything and says so. A
key is free from data.gov.in; there is no bundled fallback, because a fallback
here would silently reintroduce synthetic data behind a source labelled real.
"""

from __future__ import annotations

import json
import math
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import dict_row  # noqa: E402

from common.db import execute_batched  # noqa: E402
from ingest.cpcb_aqi import compute  # noqa: E402
from pipeline.measured_air import overlay  # noqa: E402

# The live CPCB resource on data.gov.in.
RESOURCE_ID = "3b01bcb8-0b14-4abf-b6f2-c1bfd384ba69"
ENDPOINT = f"https://api.data.gov.in/resource/{RESOURCE_ID}"

# How far a station may be from a zone's centre and still be taken as measuring
# it. Air quality varies over kilometres, not metres, so a few kilometres is
# defensible; fifteen is not, and picking "nearest" with no bound is how a
# suburb ends up reporting a monitored junction's air.
MAX_STATION_KM = 8.0

SOURCE_CODE = "cpcb-air-quality"

# data.gov.in names pollutants in its own way; map to the CPCB table's keys.
POLLUTANT_ALIASES = {
    "PM2.5": "PM2.5",
    "PM10": "PM10",
    "NO2": "NO2",
    "SO2": "SO2",
    "CO": "CO",
    "OZONE": "OZONE",
    "O3": "OZONE",
    "NH3": "NH3",
}


@dataclass(frozen=True)
class Station:
    name: str
    city: str
    latitude: float
    longitude: float
    measured_at: datetime
    concentrations: dict[str, float]


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Great-circle distance. Straight-line degrees are not distance in India."""
    radius = 6371.0
    dlat = math.radians(lat2 - lat1)
    dlon = math.radians(lon2 - lon1)
    a = (math.sin(dlat / 2) ** 2
         + math.cos(math.radians(lat1)) * math.cos(math.radians(lat2)) * math.sin(dlon / 2) ** 2)
    return 2 * radius * math.asin(math.sqrt(a))


def _fetch_page(api_key: str, limit: int, offset: int, attempts: int,
                timeout: float) -> list[dict]:
    """One page, retried. A dropped connection is not a reason to lose the run."""
    query = urllib.parse.urlencode({
        "api-key": api_key, "format": "json", "limit": limit, "offset": offset,
    })
    request = urllib.request.Request(f"{ENDPOINT}?{query}",
                                     headers={"Accept": "application/json"})

    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                payload = json.load(response)
            return payload.get("records", [])
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
            last = exc
            if attempt < attempts:
                print(f"    offset {offset}: attempt {attempt} failed ({exc}); retrying",
                      flush=True)
                time.sleep(5 * attempt)

    raise RuntimeError(
        f"CPCB feed unreachable at offset {offset} after {attempts} attempts: {last}")


def fetch(api_key: str, *, page_size: int = 500, max_records: int = 20_000,
          attempts: int = 3, timeout: float = 90.0) -> list[dict]:
    """Read the national feed a page at a time.

    Asking for the whole country in one request did not work. The first
    scheduled run timed out at thirty seconds; raising the timeout and retrying
    produced three HTTP 502s from the gateway, each after about a minute. The
    same endpoint answers a one-record request in under a second, so the size of
    the request was the problem, not the service.

    Paging also fixes a quieter bug. The feed returns a row per pollutant per
    station — roughly six for each of several hundred stations — so the previous
    `limit=2000` was already truncating the national feed, and doing it
    silently: a station missing from the response is indistinguishable from a
    station that reported nothing.

    `max_records` is a stop, not an expectation. It bounds a feed that has grown
    or a server that pages forever; reaching it says so rather than returning a
    quietly partial answer.
    """
    records: list[dict] = []
    offset = 0

    while len(records) < max_records:
        page = _fetch_page(api_key, page_size, offset, attempts, timeout)
        records.extend(page)
        if len(page) < page_size:
            return records
        offset += page_size

    print(f"  stopped at {max_records} records; the feed had more", flush=True)
    return records


def parse_stations(records: list[dict]) -> list[Station]:
    """Fold per-pollutant rows into one reading per station.

    The feed returns a row per pollutant per station, so a station's PM2.5 and
    its NO2 arrive as separate records that have to be recombined before an
    index can be computed from them.
    """
    grouped: dict[tuple[str, str], dict] = {}

    for record in records:
        station = (record.get("station") or "").strip()
        city = (record.get("city") or "").strip()
        if not station:
            continue

        pollutant = POLLUTANT_ALIASES.get((record.get("pollutant_id") or "").strip().upper())
        raw = record.get("pollutant_avg")
        if pollutant is None or raw in (None, "", "NA"):
            # A station that reported "NA" for a pollutant has not measured it.
            # Treating that as zero would read as pristine air.
            continue

        try:
            value = float(raw)
            lat = float(record["latitude"])
            lon = float(record["longitude"])
        except (TypeError, ValueError, KeyError):
            continue

        entry = grouped.setdefault((city, station), {
            "lat": lat, "lon": lon,
            "measured_at": record.get("last_update"),
            "concentrations": {},
        })
        entry["concentrations"][pollutant] = value

    stations: list[Station] = []
    for (city, station), entry in grouped.items():
        stations.append(Station(
            name=station,
            city=city,
            latitude=entry["lat"],
            longitude=entry["lon"],
            measured_at=_parse_moment(entry["measured_at"]),
            concentrations=entry["concentrations"],
        ))
    return stations


def _parse_moment(raw: str | None) -> datetime:
    """The feed's timestamp, or now.

    Its format is "DD-MM-YYYY HH:MM:SS" in IST. Falling back to now is
    deliberate and safe here: a reading whose time cannot be read is still a
    reading taken recently, and the alternative is discarding real data over a
    formatting change upstream.
    """
    if raw:
        for fmt in ("%d-%m-%Y %H:%M:%S", "%Y-%m-%d %H:%M:%S", "%d-%m-%Y %H:%M"):
            try:
                naive = datetime.strptime(raw.strip(), fmt)
                # IST, which is what CPCB publishes in.
                return naive.replace(tzinfo=timezone.utc).astimezone(timezone.utc)
            except ValueError:
                continue
    return datetime.now(timezone.utc)


def main() -> int:
    api_key = os.environ.get("CPCB_API_KEY")
    if not api_key:
        print("CPCB_API_KEY is not set. Nothing was fetched and nothing was written.\n"
              "A key is free from https://data.gov.in — there is deliberately no fallback,\n"
              "because generated readings behind a source labelled real would be worse\n"
              "than no readings at all.")
        return 1

    dsn = os.environ.get("CITYPULSE_PG_DSN")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1

    stations = parse_stations(fetch(api_key))
    print(f"  {len(stations)} stations reported")

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        with connection.cursor() as cursor:
            cursor.execute("SELECT id FROM data_sources WHERE code = %s", (SOURCE_CODE,))
            row = cursor.fetchone()
            if row is None:
                print(f"No data source '{SOURCE_CODE}'. Migration V16 seeds it.")
                return 1
            source_id = row["id"]

            cursor.execute("""
                SELECT z.id, z.code, z.center_latitude AS lat, z.center_longitude AS lon
                FROM zones z WHERE z.active AND z.deleted_at IS NULL
            """)
            zones = cursor.fetchall()

        rows = []
        matched = 0
        for station in stations:
            result = compute(station.concentrations)
            if result is None:
                # CPCB would not publish an index from this either.
                continue

            nearest = None
            nearest_km = MAX_STATION_KM
            for zone in zones:
                km = haversine_km(station.latitude, station.longitude,
                                  float(zone["lat"]), float(zone["lon"]))
                if km < nearest_km:
                    nearest, nearest_km = zone, km

            if nearest is None:
                # No monitored zone is near enough for this station to be
                # measuring it. Dropped rather than attached to whatever
                # happened to be closest.
                continue

            matched += 1
            c = station.concentrations
            rows.append((
                str(uuid.uuid4()), nearest["id"], source_id, station.measured_at,
                result.aqi, result.category,
                c.get("PM2.5"), c.get("PM10"), c.get("NO2"), c.get("OZONE"), c.get("CO"),
                # The reason these rows exist: measured, not generated.
                False,
            ))

        if rows:
            execute_batched(connection, """
                INSERT INTO air_quality_events
                    (event_id, zone_id, source_id, event_time, aqi, category,
                     pm25, pm10, no2, o3, co, demo_data)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (event_id) DO NOTHING
            """, rows)

            with connection.cursor() as cursor:
                # Activated here rather than by a migration. V16 seeds this
                # source PAUSED and says it "becomes ACTIVE when someone
                # configures a key" — a delivery is the evidence that one is
                # configured, and it is evidence this deployment produced. A
                # migration would mark it ACTIVE everywhere, including forks with
                # no key, where the Data Health page would then report a silent
                # feed: a fault report for a deliberate state.
                cursor.execute(
                    "UPDATE data_sources SET last_ingested_at = now(), status = 'ACTIVE' "
                    "WHERE id = %s", (source_id,))
            connection.commit()

            # The curated windows covering these readings were built before the
            # readings arrived, from generated air. Written to the raw table and
            # left there, a measurement is something nothing reads: the
            # dashboard reads zone_metrics.
            overlaid = overlay(connection)

    print(f"  {matched} stations within {MAX_STATION_KM} km of a monitored zone")
    print(f"  {len(rows)} real readings written (demo_data = false)")
    print(f"  {overlaid if rows else 0} curated windows now report measured air")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
