"""Event contracts shared by the generator, the Spark job and the local runner.

These dataclasses are the wire format. Kafka carries their JSON form, the Spark
job parses back into the same shapes, and the validation rules in
`validation.py` are written against them — so a producer and a consumer cannot
drift apart without a test failing.

Nothing here imports Spark, Kafka or psycopg. That is deliberate: this module
has to stay importable in a plain interpreter so the rules can be unit tested
without a cluster.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from .compat import StrEnum
from typing import Any


SCHEMA_VERSION = 1

# Decimal places `occupancy_ratio` is serialised and stored with, matching
# NUMERIC(6,4) in the schema.
#
# Exported because a derived label must be computed from the *rounded* value,
# never the full-precision one. Deriving first and rounding second lets a
# reading of 0.550004 be labelled MODERATE and then stored as 0.5500, which
# reads as NORMAL — the number and its label disagreeing by one ulp of
# rounding. Every band boundary is exposed to this.
OCCUPANCY_PRECISION = 4


class EventType(StrEnum):
    TRAFFIC = "TRAFFIC"
    WEATHER = "WEATHER"
    AIR_QUALITY = "AIR_QUALITY"
    INCIDENT = "INCIDENT"
    CITY_EVENT = "CITY_EVENT"


class CongestionLevel(StrEnum):
    NORMAL = "NORMAL"
    MODERATE = "MODERATE"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class AqiCategory(StrEnum):
    """CPCB bands, which is the scale the seeded Indian cities are reported on."""

    GOOD = "GOOD"
    SATISFACTORY = "SATISFACTORY"
    MODERATE = "MODERATE"
    POOR = "POOR"
    VERY_POOR = "VERY_POOR"
    SEVERE = "SEVERE"


class WeatherCondition(StrEnum):
    CLEAR = "CLEAR"
    CLOUDY = "CLOUDY"
    OVERCAST = "OVERCAST"
    LIGHT_RAIN = "LIGHT_RAIN"
    RAIN = "RAIN"
    HEAVY_RAIN = "HEAVY_RAIN"
    THUNDERSTORM = "THUNDERSTORM"
    FOG = "FOG"
    HAZE = "HAZE"


class IncidentType(StrEnum):
    ACCIDENT = "ACCIDENT"
    BREAKDOWN = "BREAKDOWN"
    ROAD_CLOSURE = "ROAD_CLOSURE"
    CONSTRUCTION = "CONSTRUCTION"
    FLOODING = "FLOODING"
    PROTEST = "PROTEST"
    FIRE = "FIRE"
    SIGNAL_FAILURE = "SIGNAL_FAILURE"
    OTHER = "OTHER"


class Severity(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class CityEventType(StrEnum):
    SPORTS = "SPORTS"
    CONCERT = "CONCERT"
    FESTIVAL = "FESTIVAL"
    CONFERENCE = "CONFERENCE"
    PARADE = "PARADE"
    POLITICAL = "POLITICAL"
    RELIGIOUS = "RELIGIOUS"
    MARKET = "MARKET"
    OTHER = "OTHER"


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def to_iso(moment: datetime) -> str:
    """ISO-8601 with an explicit offset.

    A naive timestamp is rejected rather than assumed to be UTC: guessing here
    is how an entire feed ends up silently shifted by hours.
    """
    if moment.tzinfo is None:
        raise ValueError("refusing to serialise a naive datetime; attach a timezone")
    return moment.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


@dataclass(slots=True)
class Envelope:
    """Fields every event carries, whatever its type.

    `event_id` is the producer's idempotency key. The database has a unique
    constraint on it, so a replayed Kafka offset or a retried Spark micro-batch
    corrects a row instead of double-counting it.
    """

    event_id: str
    event_type: EventType
    source_code: str
    event_time: datetime
    schema_version: int = SCHEMA_VERSION
    # PRD §42: synthetic data is labelled at the point of production, not at the
    # point of display, so no consumer can lose the label along the way.
    demo_data: bool = True

    def base_dict(self) -> dict[str, Any]:
        return {
            "event_id": self.event_id,
            "event_type": str(self.event_type),
            "source_code": self.source_code,
            "event_time": to_iso(self.event_time),
            "schema_version": self.schema_version,
            "demo_data": self.demo_data,
        }


@dataclass(slots=True)
class TrafficEvent:
    envelope: Envelope
    zone_code: str
    vehicle_count: int
    average_speed_kph: float
    occupancy_ratio: float
    congestion_level: CongestionLevel

    def to_dict(self) -> dict[str, Any]:
        return self.envelope.base_dict() | {
            "zone_code": self.zone_code,
            "vehicle_count": self.vehicle_count,
            "average_speed_kph": round(self.average_speed_kph, 2),
            "occupancy_ratio": round(self.occupancy_ratio, OCCUPANCY_PRECISION),
            "congestion_level": str(self.congestion_level),
        }


@dataclass(slots=True)
class WeatherEvent:
    envelope: Envelope
    city_slug: str
    temperature_c: float
    humidity_pct: float
    precipitation_mm_h: float
    wind_speed_kph: float
    visibility_km: float | None
    condition: WeatherCondition

    def to_dict(self) -> dict[str, Any]:
        return self.envelope.base_dict() | {
            "city_slug": self.city_slug,
            "temperature_c": round(self.temperature_c, 2),
            "humidity_pct": round(self.humidity_pct, 2),
            "precipitation_mm_h": round(self.precipitation_mm_h, 2),
            "wind_speed_kph": round(self.wind_speed_kph, 2),
            "visibility_km": None if self.visibility_km is None else round(self.visibility_km, 2),
            "condition": str(self.condition),
        }


@dataclass(slots=True)
class AirQualityEvent:
    envelope: Envelope
    zone_code: str
    aqi: int
    pm25: float | None = None
    pm10: float | None = None
    no2: float | None = None
    o3: float | None = None
    co: float | None = None

    def to_dict(self) -> dict[str, Any]:
        # `category` is intentionally absent: the pipeline derives it from `aqi`
        # so the label and the number cannot disagree (see transforms.py).
        return self.envelope.base_dict() | {
            "zone_code": self.zone_code,
            "aqi": self.aqi,
            "pm25": None if self.pm25 is None else round(self.pm25, 2),
            "pm10": None if self.pm10 is None else round(self.pm10, 2),
            "no2": None if self.no2 is None else round(self.no2, 2),
            "o3": None if self.o3 is None else round(self.o3, 2),
            "co": None if self.co is None else round(self.co, 3),
        }


@dataclass(slots=True)
class IncidentEvent:
    envelope: Envelope
    zone_code: str
    external_id: str
    incident_type: IncidentType
    severity: Severity
    status: str
    latitude: float
    longitude: float
    started_at: datetime
    description: str | None = None
    lanes_blocked: int | None = None
    resolved_at: datetime | None = None

    def to_dict(self) -> dict[str, Any]:
        return self.envelope.base_dict() | {
            "zone_code": self.zone_code,
            "external_id": self.external_id,
            "incident_type": str(self.incident_type),
            "severity": str(self.severity),
            "status": self.status,
            "description": self.description,
            "latitude": round(self.latitude, 6),
            "longitude": round(self.longitude, 6),
            "lanes_blocked": self.lanes_blocked,
            "started_at": to_iso(self.started_at),
            "resolved_at": None if self.resolved_at is None else to_iso(self.resolved_at),
        }


@dataclass(slots=True)
class CityEventEvent:
    envelope: Envelope
    zone_code: str
    external_id: str
    event_category: CityEventType
    name: str
    starts_at: datetime
    ends_at: datetime
    venue: str | None = None
    expected_attendance: int | None = None
    status: str = "SCHEDULED"

    def to_dict(self) -> dict[str, Any]:
        return self.envelope.base_dict() | {
            "zone_code": self.zone_code,
            "external_id": self.external_id,
            "event_category": str(self.event_category),
            "name": self.name,
            "venue": self.venue,
            "expected_attendance": self.expected_attendance,
            "starts_at": to_iso(self.starts_at),
            "ends_at": to_iso(self.ends_at),
            "status": self.status,
        }


AnyEvent = TrafficEvent | WeatherEvent | AirQualityEvent | IncidentEvent | CityEventEvent


def serialise(event: AnyEvent) -> str:
    """Compact JSON, one event per line — the format Kafka and the JSONL sink share."""
    return json.dumps(event.to_dict(), separators=(",", ":"), sort_keys=True)


def partition_key(event: AnyEvent) -> str:
    """Kafka partition key.

    Keyed by zone (or city for weather) so every event for one place lands on
    one partition and stays in order. Windowed aggregation depends on that
    ordering; keying by event id would scatter a zone across partitions and
    make per-zone windows arrive out of sequence.
    """
    if isinstance(event, WeatherEvent):
        return event.city_slug
    return event.zone_code
