#!/usr/bin/env python
"""Ask TomTom what it knows about each zone centre, and write nothing down.

    CITYPULSE_PG_DSN=... TOMTOM_API_KEY=... python scripts/probe_tomtom.py --limit 10

Traffic is the last invented feed that matters: `occupancy_ratio` is what risk,
anomalies, forecasts and alerts are all computed from, so it is the one whose
provenance changes what the product is worth. Before any of that is built this
answers the only question that can cancel the build — **does the feed actually
describe these sixty-two places?** Nothing here writes to the database, seeds a
source, or touches `zone_metrics`. It asks and it prints.

Three things it measures, none of which a "200 OK" would tell you:

**How far the answer snapped.** Flow Segment Data takes a point and replies
about the nearest road it has, at any distance. A zone centre that lands in a
park gets the answer for a highway two kilometres away, and attributing that to
the zone is exactly the failure `MAX_STATION_KM` exists to stop in
`ingest.waqi`. The distance from the zone centre to the nearest point of the
returned segment is computed and printed for every zone, so the cutoff for the
real ingester is chosen from the distribution rather than guessed.

**How much of the answer is measurement.** TomTom returns `confidence`, 0 to 1,
describing how much of the speed came from live probes rather than from its own
historical model. That maps onto this platform's own vocabulary and must:
a low-confidence figure is MODELLED at best, and calling it MEASURED because it
arrived over HTTP would put the wrong word on the dashboard.

**What the free tier costs.** Every request is counted and reported against the
daily allowance, because 62 zones on an hourly schedule is 1,488 requests a day
and the decision to build depends on that fitting.

WHAT THIS DELIBERATELY DOES NOT DO

It does not convert anything to `occupancy_ratio`. TomTom reports a *speed*
ratio and the platform stores a *fullness* ratio; they move in opposite
directions and share no scale. A conversion is a modelling decision that needs
its own justification and its own test, and inventing one inside a probe is how
a forecast ends up wearing another metric's unit. The raw speeds are printed and
the translation is left to whoever writes the ingester, on purpose.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import tuple_row  # noqa: E402

from ingest.air_store import haversine_km  # noqa: E402
# The endpoint and both thresholds come from the ingester rather than being
# restated here. This probe is what a coverage decision gets made from, so a
# copy that drifted from the module doing the real attribution would report a
# coverage the pipeline does not actually achieve — which is the same failure as
# the disclosure sentence that lived in five files until four went stale.
from ingest.tomtom import (  # noqa: E402
    ENDPOINT, MAX_SNAP_KM, MIN_CONFIDENCE_MEASURED as LIVE_CONFIDENCE,
)

#: TomTom's documented free allowance for non-tile requests, per day. Printed
#: beside the count this run spent so the hourly arithmetic is visible rather
#: than remembered. Their pricing was revised in July 2026 — if this number is
#: stale the probe is the place that will show it, because a 403 quota response
#: is printed in full like every other status.
FREE_NON_TILE_REQUESTS_PER_DAY = 2500


@dataclass(frozen=True)
class Zone:
    id: int
    city: str
    code: str
    latitude: float
    longitude: float


@dataclass(frozen=True)
class Answer:
    """What one zone's request produced. `error` set means the rest is absent."""
    zone: Zone
    status: int
    error: str | None = None
    frc: str | None = None
    current_speed: float | None = None
    free_flow_speed: float | None = None
    confidence: float | None = None
    road_closure: bool | None = None
    snap_km: float | None = None


def active_zones(connection: psycopg.Connection) -> list[Zone]:
    with connection.cursor(row_factory=tuple_row) as cursor:
        cursor.execute("""
            SELECT z.id, c.slug, z.code, z.center_latitude, z.center_longitude
              FROM zones z
              JOIN cities c ON c.id = z.city_id
             WHERE z.active AND z.deleted_at IS NULL AND c.deleted_at IS NULL
             ORDER BY c.slug, z.code
        """)
        return [Zone(int(i), str(city), str(code), float(lat), float(lon))
                for i, city, code, lat, lon in cursor.fetchall()]


def ask(zone: Zone, key: str, *, timeout: float = 30.0) -> Answer:
    """One request. Every failure returns an Answer carrying its status.

    Nothing is retried and nothing is swallowed. A probe that quietly retries
    reports a success rate the hourly job will not reproduce, and a probe that
    catches an error to keep the loop tidy is how "Invalid key" went unread on
    an hourly schedule for days.
    """
    query = urllib.parse.urlencode({
        "key": key,
        "point": f"{zone.latitude:.6f},{zone.longitude:.6f}",
        # Stated rather than defaulted. The platform is metric throughout and an
        # implicit unit is a mismatch waiting for someone to find it on a card.
        "unit": "KMPH",
    })
    request = urllib.request.Request(f"{ENDPOINT}?{query}",
                                     headers={"Accept": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status = response.status
            payload = json.load(response)
    except urllib.error.HTTPError as exc:
        # The body carries TomTom's own explanation — quota, bad key, bad point.
        # Printed verbatim, because the summary would be this file's guess.
        body = exc.read(600).decode("utf-8", "replace").strip()
        return Answer(zone, exc.code, error=body or exc.reason)
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        return Answer(zone, 0, error=f"{type(exc).__name__}: {exc}")

    segment = payload.get("flowSegmentData")
    if not isinstance(segment, dict):
        return Answer(zone, status, error=f"no flowSegmentData in {payload!r:.200}")

    points = (segment.get("coordinates") or {}).get("coordinate") or []
    snap = min(
        (haversine_km(zone.latitude, zone.longitude,
                      float(p["latitude"]), float(p["longitude"]))
         for p in points if "latitude" in p and "longitude" in p),
        default=None,
    )

    return Answer(
        zone, status,
        frc=segment.get("frc"),
        current_speed=segment.get("currentSpeed"),
        free_flow_speed=segment.get("freeFlowSpeed"),
        confidence=segment.get("confidence"),
        road_closure=segment.get("roadClosure"),
        snap_km=snap,
    )


def report(answers: list[Answer]) -> None:
    print(f"\n{'zone':<28} {'HTTP':>4} {'snap':>7} {'now':>6} {'free':>6} "
          f"{'ratio':>6} {'conf':>5}  frc")
    print("-" * 82)
    for a in answers:
        name = f"{a.zone.city}/{a.zone.code}"[:28]
        if a.error is not None:
            print(f"{name:<28} {a.status:>4}  {a.error[:44]}")
            continue

        # Speed ratio, printed as itself. It is not occupancy — see the module
        # docstring. Named 'ratio' in the header and nothing more.
        #
        # Tested against None, not truthiness. A currentSpeed of 0.0 is a road
        # at a standstill — the most important thing this feed can report — and
        # `if a.current_speed` makes it render identically to a zone that
        # answered nothing. Free-flow is the divisor and zero there is not a
        # standstill but a segment with no reference speed, so it stays absent.
        ratio = (a.current_speed / a.free_flow_speed
                 if a.current_speed is not None and a.free_flow_speed
                 else None)

        # Every field is shown as an em dash when absent rather than as a zero.
        # A speed of 0.0 means a road at a standstill and is a thing this feed
        # genuinely reports; a missing speed is not that.
        def num(value: float | None, width: int, places: int) -> str:
            return "—".rjust(width) if value is None else f"{value:{width}.{places}f}"

        print(f"{name:<28} {a.status:>4} {num(a.snap_km, 6, 2)}k "
              f"{num(a.current_speed, 6, 1)} {num(a.free_flow_speed, 6, 1)} "
              f"{num(ratio, 6, 3)} {num(a.confidence, 5, 2)}  {a.frc or '—'}")

    ok = [a for a in answers if a.error is None]
    near = [a for a in ok if a.snap_km is not None and a.snap_km <= MAX_SNAP_KM]
    live = [a for a in near
            if a.confidence is not None and a.confidence >= LIVE_CONFIDENCE]
    closed = [a for a in ok if a.road_closure]

    print(f"\n  {len(answers)} zones asked about, {len(answers)} requests spent")
    print(f"  {len(ok)} answered; {len(answers) - len(ok)} did not")
    print(f"  {len(near)} matched a road within {MAX_SNAP_KM:.0f} km of the zone centre")
    print(f"  {len(live)} of those report confidence >= {LIVE_CONFIDENCE} "
          f"(live probes rather than TomTom's historical model)")
    if closed:
        # Named, not counted. A closure is the one reading here that a person
        # would want to go and look at, and "1 report a road closure" sends
        # them back to the table to find which.
        where = ", ".join(f"{a.zone.city}/{a.zone.code}" for a in closed)
        print(f"  {len(closed)} report a road closure: {where}")

    hourly = len(answers) * 24
    print(f"\n  On an hourly schedule that is {hourly:,} requests a day "
          f"against a free allowance of {FREE_NON_TILE_REQUESTS_PER_DAY:,}"
          f"{' — over' if hourly > FREE_NON_TILE_REQUESTS_PER_DAY else ''}.")
    if len(near) < len(answers):
        print(f"  {len(answers) - len(near)} zones would keep generated traffic "
              f"and stay SYNTHETIC.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--limit", type=int, default=None,
                        help="ask about only the first N zones, to spend fewer "
                             "requests on a first look")
    arguments = parser.parse_args()

    dsn = os.environ.get("CITYPULSE_PG_DSN")
    key = os.environ.get("TOMTOM_API_KEY")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1
    if not key:
        print("TOMTOM_API_KEY is required. A free key is issued at "
              "https://developer.tomtom.com/ and works immediately.")
        return 1

    with psycopg.connect(dsn) as connection:
        zones = active_zones(connection)

    if not zones:
        print("No active zones to ask about.")
        return 1
    if arguments.limit is not None:
        zones = zones[:arguments.limit]

    print(f"Asking TomTom Flow Segment Data about {len(zones)} zone centres.")
    answers = [ask(zone, key) for zone in zones]
    report(answers)

    # Non-zero when nothing came back at all, so a CI step reading this fails
    # loudly rather than printing a table of errors under a green tick.
    return 0 if any(a.error is None for a in answers) else 1


if __name__ == "__main__":
    raise SystemExit(main())
