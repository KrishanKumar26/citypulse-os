"""Windowed aggregation from validated events into curated zone metrics.

Pure: takes validated payloads, returns window dictionaries. No database, no
clock, no Spark. The Spark job calls this per micro-batch and the local runner
calls it over a file, so both produce identical windows from identical input —
which is the only way the two paths can be trusted to agree.
"""

from __future__ import annotations

from collections import defaultdict
from datetime import datetime, timedelta
from statistics import mean
from typing import Any, Iterable, Sequence

from common.transforms import (
    aqi_category,
    congestion_level,
    risk_level,
    risk_score,
    window_start,
)


DEFAULT_WINDOW = timedelta(minutes=5)

#: Best air first. Kept as a literal rather than imported from
#: pipeline.provenance so this module stays pure — it is the one piece of
#: the pipeline both Spark and the local runner execute, and it must not pull in
#: psycopg to fold a batch of dictionaries.
PRECEDENCE = ("MEASURED", "MODELLED", "SYNTHETIC")


def _provenance(event: dict) -> str:
    """Where one reading came from — air or weather, the rule is the same.

    An event may say so outright. One that does not is read from `demo_data`,
    which is the only thing the generator's own events carry: TRUE is this
    platform inventing a number, and FALSE — on this path — is the historical
    shape of a measured reading, from before a model was a third possibility.
    Real feeds do not travel this path at all; they are written straight to
    `air_quality_events` and applied by `pipeline.provenance`, which reads
    the provenance from the source rather than inferring it.
    """
    label = event.get("provenance")
    if label in PRECEDENCE:
        return label
    return "SYNTHETIC" if event.get("demo_data", True) is not False else "MEASURED"


def _best_provenance(air_rows: list[dict]) -> list[dict]:
    """The subset of readings with the best provenance any of them has."""
    if not air_rows:
        return []
    best = min(PRECEDENCE.index(_provenance(e)) for e in air_rows)
    return [e for e in air_rows if PRECEDENCE.index(_provenance(e)) == best]


def _parse(value: Any) -> datetime | None:
    if isinstance(value, datetime):
        return value
    if isinstance(value, str):
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            return None
    return None


def aggregate(
    events: Iterable[dict],
    *,
    zone_ids: dict[str, int],
    zone_city: dict[str, str],
    window: timedelta = DEFAULT_WINDOW,
    stats: dict | None = None,
) -> list[dict]:
    """Fold validated events into one row per zone per window.

    Weather is measured per city but reported per zone, so each city's readings
    are indexed by window and then attached to every zone in that city. Doing it
    the other way — a join per zone-window at read time — would put a query on
    the dashboard's hot path for a value that never varies within a city.

    A window is emitted for any zone with at least one signal in it. Absent
    signals stay null rather than defaulting to zero: "no air quality reading"
    and "AQI is 0" are different facts and the dashboard must be able to tell
    them apart.

    `stats`, if given, is filled with what this stage did and did not keep. Two
    kinds of event are dropped here and nothing was counting either: one whose
    timestamp will not parse, and one for a zone code the catalogue does not
    know. Both are silent — the window simply never appears — so a mis-seeded
    zone or a malformed feed could remove a junction from the dashboard with no
    trace anywhere. Optional so the Spark job and the tests are unaffected.
    """
    seen = 0
    dropped_no_timestamp = 0
    dropped_unknown_zone = 0
    traffic: dict[tuple[str, datetime], list[dict]] = defaultdict(list)
    air: dict[tuple[str, datetime], list[dict]] = defaultdict(list)
    weather_by_city: dict[tuple[str, datetime], list[dict]] = defaultdict(list)
    incidents: list[dict] = []
    city_events: list[dict] = []

    for event in events:
        seen += 1
        event_type = event.get("event_type")
        moment = _parse(event.get("event_time"))
        if moment is None:
            dropped_no_timestamp += 1
            continue
        bucket = window_start(moment, window)

        if event_type == "TRAFFIC":
            traffic[(event["zone_code"], bucket)].append(event)
        elif event_type == "AIR_QUALITY":
            air[(event["zone_code"], bucket)].append(event)
        elif event_type == "WEATHER":
            weather_by_city[(event["city_slug"], bucket)].append(event)
        elif event_type == "INCIDENT":
            incidents.append(event)
        elif event_type == "CITY_EVENT":
            city_events.append(event)

    incidents = _reconcile_incidents(incidents)

    keys = set(traffic) | set(air)
    # A zone with only weather still deserves a window, since weather is a
    # signal the dashboard shows.
    for (city_slug, bucket) in weather_by_city:
        for zone_code, zone_city_slug in zone_city.items():
            if zone_city_slug == city_slug:
                keys.add((zone_code, bucket))

    rows: list[dict] = []
    for zone_code, bucket in sorted(keys, key=lambda k: (k[0], k[1])):
        zone_id = zone_ids.get(zone_code)
        if zone_id is None:
            dropped_unknown_zone += 1
            continue

        window_end = bucket + window
        traffic_rows = traffic.get((zone_code, bucket), [])
        air_rows = air.get((zone_code, bucket), [])
        city_slug = zone_city.get(zone_code)
        weather_rows = weather_by_city.get((city_slug, bucket), []) if city_slug else []

        occupancy = mean(float(e["occupancy_ratio"]) for e in traffic_rows) if traffic_rows else None
        speed = mean(float(e["average_speed_kph"]) for e in traffic_rows) if traffic_rows else None
        # Vehicle counts are summed, not averaged: each reading is a count of
        # vehicles observed in its interval, so the window total is their sum.
        vehicles = sum(int(e["vehicle_count"]) for e in traffic_rows) if traffic_rows else None

        # Only the best provenance present contributes, and the others are not
        # averaged in. The mean of an instrument and a simulation is neither —
        # it cannot be pointed at, and it would carry the label of the better
        # half. A zone with no real reading keeps its generated AQI and says so.
        contributing_air = _best_provenance(air_rows)
        aqi = round(mean(float(e["aqi"]) for e in contributing_air)) if contributing_air else None
        aqi_source = _provenance(contributing_air[0]) if contributing_air else None
        temperature = mean(float(e["temperature_c"]) for e in weather_rows) if weather_rows else None
        precipitation = (
            mean(float(e["precipitation_mm_h"]) for e in weather_rows) if weather_rows else None
        )
        # The worst condition in the window, not the last one: a five-minute
        # window containing a thunderstorm should not report CLEAR because the
        # final reading happened to land after it passed.
        condition = None
        if weather_rows:
            severity_order = [
                "CLEAR", "CLOUDY", "OVERCAST", "HAZE", "FOG",
                "LIGHT_RAIN", "RAIN", "HEAVY_RAIN", "THUNDERSTORM",
            ]
            condition = max(
                (e["condition"] for e in weather_rows),
                key=lambda c: severity_order.index(c) if c in severity_order else 0,
            )

        active_incidents = sum(
            1 for i in incidents
            if i.get("zone_code") == zone_code and _overlaps_incident(i, bucket, window_end)
        )
        active_events = sum(
            1 for e in city_events
            if e.get("zone_code") == zone_code and _overlaps_event(e, bucket, window_end)
        )

        score = risk_score(
            occupancy_ratio=occupancy,
            aqi=aqi,
            active_incidents=active_incidents,
            precipitation_mm_h=precipitation,
        )

        rows.append({
            "zone_id": zone_id,
            "zone_code": zone_code,
            "window_start": bucket,
            "window_end": window_end,
            "vehicle_count": vehicles,
            "average_speed_kph": round(speed, 2) if speed is not None else None,
            "occupancy_ratio": round(occupancy, 4) if occupancy is not None else None,
            "congestion_level": str(congestion_level(occupancy)) if occupancy is not None else None,
            "aqi": aqi,
            "aqi_category": str(aqi_category(aqi)) if aqi is not None else None,
            "temperature_c": round(temperature, 2) if temperature is not None else None,
            "precipitation_mm_h": round(precipitation, 2) if precipitation is not None else None,
            "weather_condition": condition,
            "active_incidents": active_incidents,
            "active_events": active_events,
            "risk_score": score,
            "risk_level": risk_level(score),
            "sample_count": len(traffic_rows) + len(air_rows) + len(weather_rows),
            "aqi_source": aqi_source,
            # Traffic on this path is always the generator's — a real feed is
            # written straight to `traffic_events` and applied by
            # `pipeline.provenance`, never batched through here. Stated from the
            # readings anyway rather than hardcoded SYNTHETIC, for the same
            # reason `demo_data` below is: a constant is correct only until it
            # is not, and nothing announces when that day arrives.
            "traffic_source": _provenance(traffic_rows[0]) if traffic_rows else None,
            # Weather arrives per city and is attached to every zone in it, so
            # its provenance is whatever the contributing readings carried —
            # the same rule as the air, one field over.
            "weather_source": _provenance(weather_rows[0]) if weather_rows else None,
            # A window is demo data unless every reading behind it was measured.
            # Written as a fact about the inputs rather than a constant: it was
            # hardcoded TRUE, which was correct only for as long as every feed
            # was generated, and stopped being correct the moment one was not.
            "demo_data": any(
                e.get("demo_data", True)
                for e in (*traffic_rows, *contributing_air, *weather_rows)
            ) or not (traffic_rows or contributing_air or weather_rows),
        })

    if stats is not None:
        stats["events_seen"] = seen
        stats["dropped_no_timestamp"] = dropped_no_timestamp
        # Counted per zone-window rather than per event: a zone the catalogue
        # does not know contributes no window, and the event count behind it is
        # not recoverable here without a second pass nobody needs.
        stats["dropped_unknown_zone"] = dropped_unknown_zone
        stats["windows_emitted"] = len(rows)

    return rows


def _reconcile_incidents(events: Sequence[dict]) -> list[dict]:
    """Collapse an incident's reports into its final known state.

    One incident arrives as several events: REPORTED while it is open, then
    CLEARED with a resolution time. Treating each as a separate incident is
    wrong in a way that compounds — the REPORTED record carries
    `resolved_at = null`, meaning "still open", so on its own it counts as
    active in *every* window for the rest of the run. Left unreconciled, a week
    of data reported tens of thousands of simultaneous incidents per zone and
    drove every risk score toward the ceiling.

    Reports are keyed by the feed's own identifier. The earliest start and any
    observed resolution win, so the order events arrive in does not change the
    result.
    """
    merged: dict[str, dict] = {}
    for event in events:
        key = event.get("external_id")
        if not key:
            # Without an identifier there is nothing to reconcile against;
            # treat it as its own incident rather than silently dropping it.
            merged[f"__anon__{id(event)}"] = dict(event)
            continue

        existing = merged.get(key)
        if existing is None:
            merged[key] = dict(event)
            continue

        started = _parse(event.get("started_at"))
        existing_started = _parse(existing.get("started_at"))
        if started and (existing_started is None or started < existing_started):
            existing["started_at"] = event["started_at"]

        # A resolution, once seen, is final — a later REPORTED must not reopen it.
        if event.get("resolved_at") and not existing.get("resolved_at"):
            existing["resolved_at"] = event["resolved_at"]
            existing["status"] = event.get("status", existing.get("status"))

    return list(merged.values())


def _overlaps_incident(incident: dict, start: datetime, end: datetime) -> bool:
    """True when the incident was open at any point inside the window."""
    started = _parse(incident.get("started_at"))
    if started is None or started >= end:
        return False
    resolved = _parse(incident.get("resolved_at"))
    return resolved is None or resolved > start


def _overlaps_event(event: dict, start: datetime, end: datetime) -> bool:
    starts = _parse(event.get("starts_at"))
    ends = _parse(event.get("ends_at"))
    if starts is None or ends is None:
        return False
    return starts < end and ends > start
