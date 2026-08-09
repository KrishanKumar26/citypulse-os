"""Real air quality from government monitoring stations, via WAQI.

    WAQI_API_TOKEN=... python -m ingest.waqi

In India the stations behind this feed are CPCB's own — the same instruments
`ingest.cpcb` reaches for, republished by the World Air Quality Index project
with an API that issues working keys. That is the whole reason this module
exists alongside that one: the data.gov.in resource answers every request with
"Key not authorised", so the readings were unreachable through the official
route while being freely available through this one.

These rows carry `demo_data = FALSE` and arrive through a source whose
provenance is MEASURED, which is what lets the dashboard say that a given number
came from an instrument rather than from this repository's imagination.

Four things it refuses to do:

**It does not place a station in a zone it is not near.** Stations are at fixed
coordinates; zones are areas with a centre. A station beyond MAX_STATION_KM is
not used at all.

**It does not put a US index in a CPCB-scaled column.** WAQI reports on the
US-EPA 2016 scale, where 192 is *Unhealthy*; CPCB puts 192 in *MODERATE*. The
sub-indices are taken back to concentrations and CPCB's own index is computed
from those — see `ingest.us_aqi` for why that inverse is exact enough to trust.

**It does not compute an index CPCB would not publish.** Fewer than three
pollutants, or no particulate among them, and the station reports nothing.

**It does not fill gaps.** A pollutant a station did not report stays null, and
the pollutant columns stay empty: what WAQI returned was an index, and a
concentration reconstructed from it is a working intermediate, not a measurement
to be republished as though the instrument had stated it.

ATTRIBUTION

WAQI's terms: "Attribution to the World Air Quality Index Project as well as
originating EPA is mandatory." The originating agency differs per station and
arrives in each response, so this writes the union of the credits for the
stations actually used into `data_sources.attribution`, and the Data Sources
screen renders them. The terms also forbid selling the data, using it in a paid
product, or redistributing it as an archive; this deployment is a free
demonstration that displays current conditions, which is the use they describe.
"""

from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import dict_row  # noqa: E402

from ingest import air_store, us_aqi  # noqa: E402
from ingest.cpcb_aqi import compute  # noqa: E402
from pipeline.air_provenance import MEASURED, overlay  # noqa: E402

ENDPOINT = "https://api.waqi.info"

SOURCE_CODE = "waqi-air-quality"

# How far a station may be from a zone's centre and still be taken as measuring
# it. Air quality varies over kilometres, not metres, so a few kilometres is
# defensible; fifteen is not.
MAX_STATION_KM = 8.0

# Padding on the box drawn around the zones, in degrees — comfortably more than
# MAX_STATION_KM at Indian latitudes, so no station that could match is outside
# the box. The box is derived from the zones rather than hardcoded to India, so
# adding a city elsewhere needs no change here.
BBOX_PADDING_DEG = 0.15

# A reading older than this is not describing the present. Stations publish
# hourly and a few run late, so three hours accommodates the stragglers while
# still refusing a station that stopped reporting last week.
MAX_READING_AGE = timedelta(hours=3)

# Curated windows this run may repoint. The refresh workflow rebuilds the last
# three hours from generated data immediately before this runs, so the overlay
# has to reach back at least that far to put the real readings back on top.
OVERLAY_WINDOW = timedelta(hours=6)

# Credit required of every consumer, whatever the station. The per-station
# credits are added to this from each response.
PROJECT_ATTRIBUTION = {"name": "World Air Quality Index Project", "url": "https://waqi.info/"}


class NotAuthorised(RuntimeError):
    """WAQI rejected the token. A configuration answer, not an outage."""


@dataclass(frozen=True)
class Station:
    uid: int
    name: str
    latitude: float
    longitude: float


def _get(path: str, params: dict, token: str, *, attempts: int = 3,
         timeout: float = 30.0) -> dict:
    """One call, retried, unwrapped.

    WAQI answers HTTP 200 with `{"status": "error"}` for a bad token, so the
    status has to be read from the body; treating a 200 as success would let a
    rejected token look like an empty city.
    """
    query = urllib.parse.urlencode({**params, "token": token})
    request = urllib.request.Request(f"{ENDPOINT}{path}?{query}",
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

        status = payload.get("status")
        if status == "ok":
            return payload.get("data")

        detail = payload.get("data")
        if isinstance(detail, str) and "key" in detail.lower():
            # Not retried: a rejected token is rejected three times too, and
            # the retries turn a one-line configuration problem into a stack
            # trace a minute later.
            raise NotAuthorised(
                f"WAQI rejected the token ({detail}). Check WAQI_API_TOKEN is the "
                f"token mailed by aqicn.org and was stored whole."
            )
        last = RuntimeError(f"WAQI returned status={status!r} data={detail!r}")
        if attempt < attempts:
            time.sleep(3 * attempt)

    raise RuntimeError(f"WAQI unreachable at {path} after {attempts} attempts: {last}")


def bounding_box(zones: list[air_store.Zone],
                 padding: float = BBOX_PADDING_DEG) -> tuple[float, float, float, float]:
    """The box that contains every zone, padded past the attribution radius."""
    lats = [z.latitude for z in zones]
    lons = [z.longitude for z in zones]
    return (min(lats) - padding, min(lons) - padding,
            max(lats) + padding, max(lons) + padding)


def stations_in(box: tuple[float, float, float, float], token: str) -> list[Station]:
    """Every station WAQI knows inside the box.

    One request for all of them. The per-station detail — pollutants, the
    moment, the credits — needs a call each, so this is used to decide which
    stations are worth asking about rather than to read their values.
    """
    lat1, lon1, lat2, lon2 = box
    data = _get("/map/bounds/", {"latlng": f"{lat1},{lon1},{lat2},{lon2}"}, token)
    if not isinstance(data, list):
        return []

    stations = []
    for entry in data:
        try:
            uid = int(entry["uid"])
            lat = float(entry["lat"])
            lon = float(entry["lon"])
        except (KeyError, TypeError, ValueError):
            continue
        name = str((entry.get("station") or {}).get("name") or f"station {uid}")
        stations.append(Station(uid=uid, name=name, latitude=lat, longitude=lon))
    return stations


def _parse_moment(time_block: dict | None) -> datetime | None:
    """The moment the station reported, in UTC, or nothing.

    Nothing rather than now. Unlike a national bulletin that is published as a
    batch, a WAQI station carries its own timestamp and the freshness check
    below depends on it; substituting the current time would make a station that
    stopped reporting in March look like it reported this hour.
    """
    if not isinstance(time_block, dict):
        return None
    raw = time_block.get("iso")
    if isinstance(raw, str):
        try:
            return datetime.fromisoformat(raw).astimezone(timezone.utc)
        except ValueError:
            pass
    epoch = time_block.get("v")
    if isinstance(epoch, (int, float)):
        # `v` is the local wall clock as an epoch, so it needs the station's
        # offset removed. Used only when `iso` is missing.
        return datetime.fromtimestamp(float(epoch), tz=timezone.utc)
    return None


def main() -> int:
    token = os.environ.get("WAQI_API_TOKEN")
    if not token:
        print("WAQI_API_TOKEN is not set. Nothing was fetched and nothing was written.\n"
              "A token is free from https://aqicn.org/data-platform/token/ — there is\n"
              "deliberately no fallback, because generated readings behind a source\n"
              "labelled measured would be worse than no readings at all.")
        return 1

    dsn = os.environ.get("CITYPULSE_PG_DSN")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        source = air_store.source_id(connection, SOURCE_CODE)
        if source is None:
            print(f"No data source '{SOURCE_CODE}'. Migration V19 seeds it.")
            return 1

        zones = air_store.active_zones(connection)
        if not zones:
            print("No active zones to attribute stations to.")
            return 1

        try:
            found = stations_in(bounding_box(zones), token)
        except NotAuthorised as exc:
            # One line, no traceback. A stack trace here describes urllib, and
            # the thing that needs changing is a secret in a settings page.
            print(f"  {exc}")
            print("  Nothing was fetched and nothing was written.")
            return 1

        print(f"  {len(found)} stations in range of the monitored cities")

        # Only stations that land in a zone are worth a detail request.
        candidates: list[tuple[Station, air_store.Zone, float]] = []
        for station in found:
            match = air_store.nearest_zone(zones, station.latitude, station.longitude,
                                           MAX_STATION_KM)
            if match is not None:
                candidates.append((station, match[0], match[1]))

        print(f"  {len(candidates)} within {MAX_STATION_KM:.0f} km of a monitored zone")

        now = datetime.now(timezone.utc)
        readings: list[air_store.Reading] = []
        credits: dict[tuple[str, str], dict] = {
            (PROJECT_ATTRIBUTION["name"], PROJECT_ATTRIBUTION["url"]): PROJECT_ATTRIBUTION,
        }
        no_index = stale = 0

        for station, zone, km in candidates:
            try:
                detail = _get(f"/feed/@{station.uid}/", {}, token)
            except NotAuthorised as exc:
                print(f"  {exc}")
                return 1
            except RuntimeError as exc:
                # One unreachable station is not a reason to lose the others.
                print(f"    {station.name}: {exc}")
                continue

            if not isinstance(detail, dict):
                continue

            moment = _parse_moment(detail.get("time"))
            if moment is None or now - moment > MAX_READING_AGE:
                stale += 1
                continue

            result = compute(us_aqi.concentrations(detail.get("iaqi") or {}))
            if result is None:
                # CPCB would not publish an index from this either.
                no_index += 1
                continue

            readings.append(air_store.Reading(
                zone_id=zone.id,
                event_time=moment,
                aqi=result.aqi,
                category=result.category,
            ))

            for credit in detail.get("attributions") or []:
                name = str(credit.get("name") or "").strip()
                url = str(credit.get("url") or "").strip()
                if name:
                    credits[(name, url)] = {"name": name, "url": url}

        if stale:
            print(f"  {stale} skipped: last reported more than "
                  f"{int(MAX_READING_AGE.total_seconds() // 3600)}h ago")
        if no_index:
            print(f"  {no_index} skipped: fewer pollutants than CPCB publishes an index from")

        written = air_store.write(connection, source, readings)
        overlaid: dict[str, int] = {}
        if written:
            air_store.mark_delivered(connection, source, sorted(
                credits.values(), key=lambda c: c["name"]))
            overlaid = overlay(connection, since=now - OVERLAY_WINDOW)

    print(f"  {written} measured readings written (demo_data = false)")
    print(f"  {overlaid.get(MEASURED, 0)} curated windows now report measured air")
    if not written:
        print("  Nothing was written, so the source stays PAUSED.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
