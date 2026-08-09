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

**It does not place a station in a zone it is not near.** Each zone is asked
which station covers it, and WAQI answers with the nearest one at *any*
distance — for a zone with no monitoring nearby that is a station in another
city. The station's own coordinates are checked against MAX_STATION_KM before
anything is attributed, and beyond it the zone keeps whatever else covers it.

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
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import dict_row  # noqa: E402

from ingest import air_store, us_aqi  # noqa: E402
from ingest.cpcb_aqi import compute  # noqa: E402
from pipeline.provenance import MEASURED, overlay  # noqa: E402

ENDPOINT = "https://api.waqi.info"

SOURCE_CODE = "waqi-air-quality"

# How far a station may be from a zone's centre and still be taken as measuring
# it. Air quality varies over kilometres, not metres, so a few kilometres is
# defensible; fifteen is not.
MAX_STATION_KM = 8.0

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
                f"WAQI rejected the token ({detail}). Two things make this happen: "
                f"the token was stored short of a character, or it was never "
                f"activated — aqicn.org mails a confirmation link and the token "
                f"does not work until it is clicked."
            )
        last = RuntimeError(f"WAQI returned status={status!r} data={detail!r}")
        if attempt < attempts:
            time.sleep(3 * attempt)

    raise RuntimeError(f"WAQI unreachable at {path} after {attempts} attempts: {last}")


def nearest_station(zone: air_store.Zone, token: str) -> tuple[dict, float] | None:
    """The station WAQI considers nearest to this zone, and how far away it is.

    One request per zone against `/feed/geo:`, rather than one request for a box
    around every city followed by a detail call per station inside it. Three
    reasons, in order of weight:

    **It is the endpoint every token can reach.** `/map/bounds/` is a separate
    grant on some tokens, so a working token could still be told "Invalid key"
    there — a rejection indistinguishable from a bad token, on the one call that
    decides whether this feed runs at all.

    **It answers the question actually being asked.** The bounds form returns
    stations and leaves this module to decide which zone each belongs to, which
    is a nearest-neighbour search reimplemented against WAQI's own. The geo form
    is asked per zone and returns the station for it.

    **A zone gets one station or none.** That is what the curated layer wants:
    the overlay averages readings at a moment, and two stations of differing
    quality inside one radius would average into a number neither reported.

    Distance is still checked here rather than trusted. `/feed/geo:` returns the
    *nearest* station at any distance — for a zone with no monitoring for two
    hundred kilometres it returns one two hundred kilometres away, and reporting
    that as the zone's air is exactly the failure MAX_STATION_KM exists to stop.
    """
    detail = _get(f"/feed/geo:{zone.latitude:.4f};{zone.longitude:.4f}/", {}, token)
    if not isinstance(detail, dict):
        return None

    geo = (detail.get("city") or {}).get("geo")
    if not isinstance(geo, list) or len(geo) < 2:
        # Without the station's own coordinates its distance is unknown, and an
        # unknown distance cannot be checked against the limit.
        return None

    try:
        km = air_store.haversine_km(zone.latitude, zone.longitude,
                                    float(geo[0]), float(geo[1]))
    except (TypeError, ValueError):
        return None

    return None if km > MAX_STATION_KM else (detail, km)


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
    raw = os.environ.get("WAQI_API_TOKEN") or ""
    # Stripped, and the fact that it needed stripping is reported below.
    # `printf %s | gh secret set` keeps the newline, and a secret is invisible
    # once stored — so a token that is correct apart from a trailing newline
    # fails exactly like a wrong one, forever, with nothing on screen to
    # distinguish them.
    token = raw.strip()
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

        now = datetime.now(timezone.utc)
        readings: list[air_store.Reading] = []
        credits: dict[tuple[str, str], dict] = {
            (PROJECT_ATTRIBUTION["name"], PROJECT_ATTRIBUTION["url"]): PROJECT_ATTRIBUTION,
        }
        covered = no_index = stale = unreachable = 0

        for zone in zones:
            try:
                match = nearest_station(zone, token)
            except NotAuthorised as exc:
                # One line, no traceback, and the run stops: every remaining
                # zone would be rejected the same way. A stack trace here
                # describes urllib, and the thing that needs changing is a
                # secret in a settings page.
                print(f"  {exc}")
                # Enough shape to tell a truncated paste from a dead token, and
                # nothing that could reconstruct it. The token travels in a
                # query string, so neither it nor the URL is ever printed.
                print(f"  The token this run used is {len(token)} characters"
                      + (f", after stripping {len(raw) - len(token)} character(s) "
                         f"of whitespace GitHub had stored with it"
                         if len(raw) != len(token) else " and had no stray whitespace")
                      + ".")
                print("  Nothing was fetched and nothing was written.")
                return 1
            except RuntimeError as exc:
                # One unreachable zone is not a reason to lose the others.
                unreachable += 1
                print(f"    {zone.code}: {exc}")
                continue

            if match is None:
                # No station within MAX_STATION_KM. The zone keeps whatever
                # covers it — CAMS, or the generator — and is not attributed a
                # station that is measuring somewhere else.
                continue

            detail, km = match
            covered += 1

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

        print(f"  {len(zones)} zones asked about")
        print(f"  {covered} have a station within {MAX_STATION_KM:.0f} km")
        if unreachable:
            print(f"  {unreachable} could not be asked")

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
