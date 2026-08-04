"""Validation rules applied to every event before it reaches curated storage.

One rule set, imported by both the Spark streaming job and the local runner, so
"valid" means the same thing regardless of which path processed the record.

The contract is that `validate` never raises and never silently drops. Every
record comes back as either `Valid` or `Rejected` carrying a reason code from
the `ingestion_dlq.reason_code` check constraint — the phase exit criterion is
that an invalid record is explainable, which needs the reason to travel with it.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from typing import Any, Final

from .events import (
    AqiCategory,
    CityEventType,
    CongestionLevel,
    EventType,
    IncidentType,
    Severity,
    WeatherCondition,
)


class ReasonCode:
    """Mirrors ck_ingestion_dlq_reason in V4__telemetry_schema.sql.

    Kept as plain constants rather than an enum so the Spark job can use them in
    a UDF without shipping an enum class to executors.
    """

    MALFORMED_JSON: Final = "MALFORMED_JSON"
    SCHEMA_MISMATCH: Final = "SCHEMA_MISMATCH"
    MISSING_REQUIRED_FIELD: Final = "MISSING_REQUIRED_FIELD"
    UNKNOWN_ZONE: Final = "UNKNOWN_ZONE"
    UNKNOWN_CITY: Final = "UNKNOWN_CITY"
    UNKNOWN_SOURCE: Final = "UNKNOWN_SOURCE"
    VALUE_OUT_OF_RANGE: Final = "VALUE_OUT_OF_RANGE"
    TIMESTAMP_INVALID: Final = "TIMESTAMP_INVALID"
    TIMESTAMP_TOO_OLD: Final = "TIMESTAMP_TOO_OLD"
    TIMESTAMP_IN_FUTURE: Final = "TIMESTAMP_IN_FUTURE"
    DUPLICATE_EVENT_ID: Final = "DUPLICATE_EVENT_ID"
    UNSUPPORTED_EVENT_TYPE: Final = "UNSUPPORTED_EVENT_TYPE"


# How far behind the watermark a record may be and still be accepted. Beyond
# this its window has already been written and correcting it would mean
# rewriting settled history, so it goes to the DLQ where it stays visible.
MAX_EVENT_LATENESS: Final = timedelta(hours=6)

# Clocks drift; a few minutes ahead is tolerated. Hours ahead is a broken
# producer, and accepting it would poison every future window it lands in.
MAX_CLOCK_SKEW: Final = timedelta(minutes=5)


@dataclass(slots=True, frozen=True)
class Valid:
    """A record that passed every rule, normalised into a flat dict."""

    event_type: str
    payload: dict[str, Any]


@dataclass(slots=True, frozen=True)
class Rejected:
    reason_code: str
    detail: str
    raw_payload: str
    event_type: str | None = None
    event_time: datetime | None = None


Outcome = Valid | Rejected


@dataclass(slots=True, frozen=True)
class ReferenceData:
    """Known zones, cities and sources, used to reject records that point nowhere.

    Loaded once from PostgreSQL and passed in rather than queried per record —
    a per-record lookup would make validation the pipeline's bottleneck.
    """

    zone_codes: frozenset[str]
    city_slugs: frozenset[str]
    source_codes: frozenset[str]

    @staticmethod
    def empty() -> "ReferenceData":
        return ReferenceData(frozenset(), frozenset(), frozenset())


# Required fields per event type, beyond the envelope.
_REQUIRED: Final[dict[str, tuple[str, ...]]] = {
    EventType.TRAFFIC: ("zone_code", "vehicle_count", "average_speed_kph", "occupancy_ratio"),
    EventType.WEATHER: ("city_slug", "temperature_c", "humidity_pct", "precipitation_mm_h", "wind_speed_kph"),
    EventType.AIR_QUALITY: ("zone_code", "aqi"),
    EventType.INCIDENT: ("zone_code", "external_id", "incident_type", "severity", "latitude", "longitude", "started_at"),
    EventType.CITY_EVENT: ("zone_code", "external_id", "event_category", "name", "starts_at", "ends_at"),
}

# Numeric bounds mirroring the CHECK constraints in V4. Duplicated here on
# purpose: the database is the last line of defence, but a violation caught at
# the database would abort a whole Spark batch, whereas caught here it costs one
# DLQ row. `test_validation_matches_schema` guards the two from drifting.
_RANGES: Final[dict[str, tuple[float, float]]] = {
    "vehicle_count": (0, 1_000_000),
    "average_speed_kph": (0, 400),
    "occupancy_ratio": (0, 10),
    "temperature_c": (-90, 60),
    "humidity_pct": (0, 100),
    "precipitation_mm_h": (0, 500),
    "wind_speed_kph": (0, 500),
    "visibility_km": (0, 200),
    "aqi": (0, 1000),
    "pm25": (0, 10_000),
    "pm10": (0, 10_000),
    "no2": (0, 10_000),
    "o3": (0, 10_000),
    "co": (0, 1_000),
    "latitude": (-90, 90),
    "longitude": (-180, 180),
    "lanes_blocked": (0, 32),
    "expected_attendance": (0, 10_000_000),
}

_ENUMS: Final[dict[str, frozenset[str]]] = {
    "congestion_level": frozenset(c.value for c in CongestionLevel),
    "condition": frozenset(c.value for c in WeatherCondition),
    "incident_type": frozenset(c.value for c in IncidentType),
    "severity": frozenset(c.value for c in Severity),
    "event_category": frozenset(c.value for c in CityEventType),
}


def parse_timestamp(value: Any) -> datetime | None:
    """Parse an ISO-8601 timestamp, requiring an explicit offset.

    A naive timestamp is rejected rather than assumed UTC — a feed that omits
    its offset is a feed whose timestamps cannot be trusted, and guessing would
    shift every reading by the producer's local offset without any error.
    """
    if not isinstance(value, str):
        return None
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return None
    return parsed.astimezone(timezone.utc)


def validate(
    raw: str,
    reference: ReferenceData,
    *,
    now: datetime | None = None,
    seen_event_ids: set[str] | None = None,
    max_lateness: timedelta = MAX_EVENT_LATENESS,
) -> Outcome:
    """Apply every rule to one raw record.

    `seen_event_ids`, when supplied, catches duplicates inside a single batch.
    Cross-batch duplicates are caught by the unique constraint on `event_id`,
    which is the only place that can be authoritative.

    `max_lateness` defaults to the streaming watermark, where the point is to
    protect settled windows from being rewritten by stragglers. A deliberate
    historical backfill is a different operation with a different guarantee, so
    it raises this explicitly rather than the rule being weakened for everyone
    — the caller has to state that it means to load old data.
    """
    now = now or datetime.now(timezone.utc)

    try:
        payload = json.loads(raw)
    except (json.JSONDecodeError, TypeError) as exc:
        return Rejected(ReasonCode.MALFORMED_JSON, str(exc)[:500], raw)

    if not isinstance(payload, dict):
        return Rejected(
            ReasonCode.SCHEMA_MISMATCH,
            f"expected a JSON object, got {type(payload).__name__}",
            raw,
        )

    event_type = payload.get("event_type")
    if event_type not in _REQUIRED:
        return Rejected(
            ReasonCode.UNSUPPORTED_EVENT_TYPE,
            f"event_type={event_type!r}",
            raw,
            event_type=event_type if isinstance(event_type, str) else None,
        )

    for envelope_field in ("event_id", "source_code", "event_time"):
        if not payload.get(envelope_field):
            return Rejected(
                ReasonCode.MISSING_REQUIRED_FIELD,
                f"envelope field {envelope_field!r} is missing or empty",
                raw,
                event_type=event_type,
            )

    event_time = parse_timestamp(payload.get("event_time"))
    if event_time is None:
        return Rejected(
            ReasonCode.TIMESTAMP_INVALID,
            f"event_time={payload.get('event_time')!r} is not ISO-8601 with an offset",
            raw,
            event_type=event_type,
        )

    if event_time > now + MAX_CLOCK_SKEW:
        return Rejected(
            ReasonCode.TIMESTAMP_IN_FUTURE,
            f"event_time is {(event_time - now).total_seconds():.0f}s ahead of now",
            raw,
            event_type=event_type,
            event_time=event_time,
        )

    if event_time < now - max_lateness:
        return Rejected(
            ReasonCode.TIMESTAMP_TOO_OLD,
            f"event_time is {(now - event_time).total_seconds():.0f}s old, "
            f"beyond the {max_lateness.total_seconds():.0f}s watermark",
            raw,
            event_type=event_type,
            event_time=event_time,
        )

    missing = [f for f in _REQUIRED[event_type] if payload.get(f) is None]
    if missing:
        return Rejected(
            ReasonCode.MISSING_REQUIRED_FIELD,
            f"missing: {', '.join(missing)}",
            raw,
            event_type=event_type,
            event_time=event_time,
        )

    if payload["source_code"] not in reference.source_codes and reference.source_codes:
        return Rejected(
            ReasonCode.UNKNOWN_SOURCE,
            f"source_code={payload['source_code']!r}",
            raw,
            event_type=event_type,
            event_time=event_time,
        )

    # Weather is city-scoped; everything else is zone-scoped.
    if event_type == EventType.WEATHER:
        if reference.city_slugs and payload["city_slug"] not in reference.city_slugs:
            return Rejected(
                ReasonCode.UNKNOWN_CITY,
                f"city_slug={payload['city_slug']!r}",
                raw,
                event_type=event_type,
                event_time=event_time,
            )
    elif reference.zone_codes and payload["zone_code"] not in reference.zone_codes:
        return Rejected(
            ReasonCode.UNKNOWN_ZONE,
            f"zone_code={payload['zone_code']!r}",
            raw,
            event_type=event_type,
            event_time=event_time,
        )

    for field_name, (low, high) in _RANGES.items():
        value = payload.get(field_name)
        if value is None:
            continue
        if isinstance(value, bool) or not isinstance(value, (int, float)):
            return Rejected(
                ReasonCode.SCHEMA_MISMATCH,
                f"{field_name} must be numeric, got {type(value).__name__}",
                raw,
                event_type=event_type,
                event_time=event_time,
            )
        # NaN and infinity pass every comparison silently; they have to be
        # caught explicitly or they reach the database as nulls or errors.
        if isinstance(value, float) and not math.isfinite(value):
            return Rejected(
                ReasonCode.VALUE_OUT_OF_RANGE,
                f"{field_name}={value}",
                raw,
                event_type=event_type,
                event_time=event_time,
            )
        if not low <= value <= high:
            return Rejected(
                ReasonCode.VALUE_OUT_OF_RANGE,
                f"{field_name}={value} outside [{low}, {high}]",
                raw,
                event_type=event_type,
                event_time=event_time,
            )

    for field_name, allowed in _ENUMS.items():
        value = payload.get(field_name)
        if value is not None and value not in allowed:
            return Rejected(
                ReasonCode.SCHEMA_MISMATCH,
                f"{field_name}={value!r} is not one of {sorted(allowed)}",
                raw,
                event_type=event_type,
                event_time=event_time,
            )

    if event_type == EventType.CITY_EVENT:
        starts = parse_timestamp(payload["starts_at"])
        ends = parse_timestamp(payload["ends_at"])
        if starts is None or ends is None:
            return Rejected(
                ReasonCode.TIMESTAMP_INVALID,
                "starts_at/ends_at must be ISO-8601 with an offset",
                raw,
                event_type=event_type,
                event_time=event_time,
            )
        if ends < starts:
            return Rejected(
                ReasonCode.VALUE_OUT_OF_RANGE,
                "ends_at is before starts_at",
                raw,
                event_type=event_type,
                event_time=event_time,
            )

    if event_type == EventType.INCIDENT:
        started = parse_timestamp(payload["started_at"])
        if started is None:
            return Rejected(
                ReasonCode.TIMESTAMP_INVALID,
                "started_at must be ISO-8601 with an offset",
                raw,
                event_type=event_type,
                event_time=event_time,
            )
        resolved_raw = payload.get("resolved_at")
        if resolved_raw is not None:
            resolved = parse_timestamp(resolved_raw)
            if resolved is None:
                return Rejected(
                    ReasonCode.TIMESTAMP_INVALID,
                    "resolved_at must be ISO-8601 with an offset",
                    raw,
                    event_type=event_type,
                    event_time=event_time,
                )
            if resolved < started:
                return Rejected(
                    ReasonCode.VALUE_OUT_OF_RANGE,
                    "resolved_at is before started_at",
                    raw,
                    event_type=event_type,
                    event_time=event_time,
                )

    if seen_event_ids is not None:
        event_id = payload["event_id"]
        if event_id in seen_event_ids:
            return Rejected(
                ReasonCode.DUPLICATE_EVENT_ID,
                f"event_id={event_id} already seen in this batch",
                raw,
                event_type=event_type,
                event_time=event_time,
            )
        seen_event_ids.add(event_id)

    normalised = dict(payload)
    normalised["event_time"] = event_time
    return Valid(event_type=event_type, payload=normalised)
