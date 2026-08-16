"""Real traffic from vehicle probes, via TomTom.

    TOMTOM_API_KEY=... python -m ingest.tomtom

The third signal to stop being invented, and the one that decides what the
product is worth: `occupancy_ratio` is what risk, anomalies, forecasts and
alerts are all computed from, and until now every figure behind them came out of
this repository's generator.

TomTom's Traffic API covers India for flow and incidents. A probe of all
sixty-two zone centres answered every one — median snap distance 60 m,
confidence 0.74 to 1.00, speed ratios spread from 0.48 to 1.00 at ten on a
Tuesday morning. That is a live feed, not a table of speed limits.

WHAT IS WRITTEN, AND WHAT IS DELIBERATELY NOT

This does not count vehicles, so it does not write a vehicle count, and it does
not write an occupancy. The platform's own BPR relationship can turn a speed
into an occupancy and the inversion is exact, but measured against 124 real
readings it put 29% of them at occupancy 0.000 — free-flowing roads reported as
empty — and moved half the scale on one km/h of TomTom's whole-number rounding.
V22 records the numbers. What is stored is what was measured: a speed, the
free-flow speed it is measured against, and their ratio.

**Provenance is decided per reading, by TomTom's own confidence.** The API
reports 0 to 1 for how much of a speed came from vehicles observed now rather
than from its historical model for that road and hour. That is precisely the
MEASURED/MODELLED distinction, so readings are filed under two source rows
accordingly — see V22. One source marked MEASURED would put an instrument's
label on a model's output every time the probes thinned out, which is the
failure the three-state provenance exists to prevent.

Four things it refuses to do:

**It does not attribute a road to a zone it is not near.** Flow Segment Data
answers about the nearest road it has at *any* distance, so a zone centre in a
park is answered for by a highway two kilometres away. The returned segment's
own shape is measured against MAX_SNAP_KM before anything is attributed, exactly
as `ingest.waqi` does with a station.

**It does not keep a reading it cannot label.** Below MIN_CONFIDENCE the value
is more model than measurement and is dropped rather than filed under MODELLED
to keep the row count up.

**It does not invent a timestamp.** Flow Segment Data describes the road now and
returns no observation time, so `event_time` is the moment of the request and is
documented as such rather than dressed up as the moment of measurement.

**It does not fill the gap it leaves.** A zone that answers nothing, snaps too
far, or reports too little confidence keeps its generated traffic and stays
SYNTHETIC. That is the honest state and the interface already has a word for it.
"""

from __future__ import annotations

import http.client
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
from psycopg.rows import dict_row  # noqa: E402

from common.db import execute_batched  # noqa: E402
# Zone, active_zones, haversine_km, source_id and mark_delivered are not about
# air. They read the zone table, measure the earth and flip a source to ACTIVE,
# and every ingester needs them. They live in a module named for the three feeds
# that happened to need them first; the name now understates its scope, and
# copying them here to match it would be the drift that name was meant to stop.
from ingest.air_store import Zone, active_zones, haversine_km, mark_delivered, source_id  # noqa: E402
from pipeline.provenance import MEASURED, MODELLED, overlay_traffic  # noqa: E402

#: Zoom 12, and this parameter is not what it looks like.
#:
#: It reads as map detail, and the first version of this module used 22 on that
#: reading — TomTom document the returned `coordinates` as "shifted from the road
#: depending on the zoom level", so the maximum looked like the way to measure
#: snap distance honestly. That reasoning was wrong, and measuring it showed why:
#: zoom does not refine one road's shape, it **selects which road answers**.
#:
#:     DEL-NZM  zoom 14 -> FRC6, 16 of 25 kph      a local road
#:     DEL-NZM  zoom 12 -> FRC2, 52 of 52 kph      the arterial
#:
#: A zone here is a district, and its traffic is the arterial running through it
#: — not whichever residential lane its centroid happened to land on. At 22 a
#: third of the zones matched minor roads and sat at exactly free flow for an
#: hour, which read like a feed that did not cover them. It was not:
#:
#:     PNQ-PMP  zoom 22 -> FRC6  1.000        zoom 12 -> FRC4  0.769
#:     AMD-NRD  zoom 14 -> FRC5  1.000        zoom 12 -> FRC2  0.780
#:     JAI-VKI  zoom 22 -> FRC5  1.000        zoom 12 -> FRC5  0.731
#:
#: Confidence rises with it too, because arterials carry more probe traffic than
#: side streets. Snap distances stay well inside MAX_SNAP_KM — the two zones
#: measured across zooms moved to 0.34 km and 0.17 km.
ENDPOINT = "https://api.tomtom.com/traffic/services/4/flowSegmentData/absolute/12/json"

SOURCE_MEASURED = "tomtom-traffic"
SOURCE_MODELLED = "tomtom-traffic-modelled"

#: At or above this, the speed is overwhelmingly live probes and the reading is
#: an observation. The probe found 58 of 62 zones here.
MIN_CONFIDENCE_MEASURED = 0.9

#: Below this, the reading is more model than measurement and is not kept at
#: all. Between the two it is written, and labelled MODELLED.
MIN_CONFIDENCE = 0.5

#: Beyond this the answer is about a different road. 2 km rather than the air
#: feeds' 8 km: a monitoring station samples an air mass that spans a city,
#: while a road two kilometres away carries different traffic than the one asked
#: about. The probe found 59 of 62 zones inside 1 km and none beyond 1.9.
MAX_SNAP_KM = 2.0

#: Curated windows this run may repoint — see ingest.waqi.
OVERLAY_WINDOW = timedelta(hours=6)


@dataclass(frozen=True)
class Reading:
    """One segment's speed for one zone, at the moment it was asked for."""
    zone_id: int
    event_time: datetime
    speed_kph: float
    free_flow_kph: float
    speed_ratio: float
    confidence: float
    snap_km: float
    #: MEASURED or MODELLED. Decided by confidence, not by the caller.
    provenance: str


def _get(zone: Zone, key: str, *, attempts: int = 3, timeout: float = 30.0) -> dict:
    """One zone's segment. Raises rather than returning a shape to re-check.

    Retried like `ingest.weather`, and for a reason measured here: the first run
    of this module over sixty-two zones lost fifteen of them to read timeouts —
    a quarter of the map — while another process was talking to the same API.
    One request per zone with no second try makes coverage a function of how
    busy the network was, and a zone that dropped out would silently keep its
    generated traffic.

    HTTP errors are not retried. A 403 for a bad key or an exhausted quota
    answers the same way however many times it is asked, and sixty-two zones
    each trying three times turns one clear failure into a slow one.
    """
    query = urllib.parse.urlencode({
        "key": key,
        "point": f"{zone.latitude:.6f},{zone.longitude:.6f}",
        # Stated rather than defaulted. The platform is metric throughout and an
        # implicit unit is a mismatch waiting to be found on a card.
        "unit": "KMPH",
    })
    request = urllib.request.Request(f"{ENDPOINT}?{query}",
                                     headers={"Accept": "application/json"})

    last: Exception | None = None
    for attempt in range(1, attempts + 1):
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return json.load(response)
        except urllib.error.HTTPError:
            raise
        # IncompleteRead is in here deliberately. It is an HTTPException and not
        # a URLError, so an earlier version of this list did not catch it and one
        # truncated response — 32,470 bytes of an expected 56,783 — took down a
        # run that had already fetched forty zones successfully. A transport that
        # can half-deliver a body needs the same retry as one that times out.
        except (urllib.error.URLError, TimeoutError, http.client.HTTPException,
                json.JSONDecodeError) as exc:
            last = exc
            if attempt < attempts:
                time.sleep(2 * attempt)

    raise RuntimeError(f"TomTom unreachable for zone {zone.code} "
                       f"after {attempts} attempts: {last}")


def read(zone: Zone, payload: dict, now: datetime) -> Reading | None:
    """One zone's traffic, or nothing if it cannot be stated.

    Returns None rather than a partial or a guess. Every rejection here is a
    zone that keeps its generated traffic and says SYNTHETIC, which is a true
    statement; a filled-in row would not be.
    """
    segment = payload.get("flowSegmentData")
    if not isinstance(segment, dict):
        return None

    current = segment.get("currentSpeed")
    free_flow = segment.get("freeFlowSpeed")
    confidence = segment.get("confidence")
    if not isinstance(current, (int, float)) or not isinstance(free_flow, (int, float)):
        return None
    if not isinstance(confidence, (int, float)):
        # No confidence means no way to tell an observation from a model, and
        # the provenance would have to be guessed.
        return None
    if free_flow <= 0:
        # Nothing to measure the current speed against. A ratio needs both.
        return None
    if confidence < MIN_CONFIDENCE:
        return None

    # How far the road that answered sits from the zone it is being attributed
    # to. Minimum over the segment's shape: the nearest point of the matched
    # road is the honest distance, not the distance to wherever its polyline
    # happens to start.
    points = (segment.get("coordinates") or {}).get("coordinate") or []
    distances = [
        haversine_km(zone.latitude, zone.longitude,
                     float(p["latitude"]), float(p["longitude"]))
        for p in points if "latitude" in p and "longitude" in p
    ]
    if not distances:
        # A segment with no shape cannot be placed, so it cannot be attributed.
        return None
    snap = min(distances)
    if snap > MAX_SNAP_KM:
        return None

    return Reading(
        zone_id=zone.id,
        # The API describes the road now and returns no observation time. This
        # is the moment of asking, which is the closest true statement
        # available; it is not a timestamp TomTom supplied.
        event_time=now,
        speed_kph=round(float(current), 2),
        free_flow_kph=round(float(free_flow), 2),
        speed_ratio=round(float(current) / float(free_flow), 4),
        confidence=round(float(confidence), 3),
        snap_km=round(snap, 3),
        provenance=(MEASURED if confidence >= MIN_CONFIDENCE_MEASURED else MODELLED),
    )


def main() -> int:
    dsn = os.environ.get("CITYPULSE_PG_DSN")
    key = os.environ.get("TOMTOM_API_KEY")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1
    if not key:
        print("TOMTOM_API_KEY absent: no key, so nothing was fetched and "
              "nothing was written. A free key is issued at "
              "https://developer.tomtom.com/ and works immediately.")
        return 1

    now = datetime.now(timezone.utc)

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        sources = {
            MEASURED: source_id(connection, SOURCE_MEASURED),
            MODELLED: source_id(connection, SOURCE_MODELLED),
        }
        missing = [code for code, found in
                   ((SOURCE_MEASURED, sources[MEASURED]),
                    (SOURCE_MODELLED, sources[MODELLED])) if found is None]
        if missing:
            print(f"No data source {', '.join(missing)}. Migration V22 seeds them.")
            return 1

        zones = active_zones(connection)
        if not zones:
            print("No zones to fetch traffic for.")
            return 1

        readings: list[Reading] = []
        unreachable = 0
        rejected = 0

        for zone in zones:
            try:
                payload = _get(zone, key)
            except urllib.error.HTTPError as exc:
                # Printed in full on the first one. A quota or key failure
                # answers every zone identically, and sixty-two copies of it
                # teaches a reader to skim the log.
                if unreachable == 0:
                    body = exc.read(300).decode("utf-8", "replace").strip()
                    print(f"  HTTP {exc.code} from TomTom: {body or exc.reason}")
                unreachable += 1
                continue
            except RuntimeError as exc:
                # Raised by _get once its retries are spent. Named per zone, so
                # a partial outage can be told apart from a total one.
                if unreachable == 0:
                    print(f"  {exc}")
                unreachable += 1
                continue

            reading = read(zone, payload, now)
            if reading is None:
                rejected += 1
                continue
            readings.append(reading)

        print(f"  {len(zones)} zones asked about")
        if unreachable:
            print(f"  {unreachable} could not be reached")
        if rejected:
            print(f"  {rejected} rejected: snapped beyond {MAX_SNAP_KM:.0f} km, "
                  f"confidence below {MIN_CONFIDENCE}, or incomplete")

        written = 0
        overlaid: dict[str, int] = {}
        if readings:
            execute_batched(connection, """
                INSERT INTO traffic_events
                    (event_id, zone_id, source_id, event_time, average_speed_kph,
                     speed_ratio, free_flow_speed_kph, confidence, demo_data)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (event_id) DO NOTHING
            """, [(
                str(uuid.uuid4()), r.zone_id, sources[r.provenance], r.event_time,
                r.speed_kph, r.speed_ratio, r.free_flow_kph, r.confidence, False,
            ) for r in readings])
            written = len(readings)

            # Only the tiers that actually delivered are activated. A source
            # flipped to ACTIVE on a run that wrote none of its kind would
            # report a working feed on the strength of the other one.
            for provenance in {r.provenance for r in readings}:
                mark_delivered(connection, sources[provenance])

            overlaid = overlay_traffic(connection, since=now - OVERLAY_WINDOW)

        measured = sum(1 for r in readings if r.provenance == MEASURED)
        print(f"  {written} readings written (demo_data = false): "
              f"{measured} measured, {written - measured} modelled")
        print(f"  {overlaid.get(MEASURED, 0)} curated windows now report measured "
              f"traffic, {overlaid.get(MODELLED, 0)} modelled")
        if not written:
            print("  Nothing was written, so the sources stay PAUSED.")

    return 0 if readings else 1


if __name__ == "__main__":
    raise SystemExit(main())
