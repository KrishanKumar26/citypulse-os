"""PostgreSQL writes for the ingestion pipeline.

Every statement here is idempotent. That is the property that makes a replay
safe: reprocessing a Kafka offset range, re-running a backfill, or retrying a
failed Spark micro-batch corrects rows rather than duplicating them. Without it
"just run it again" would silently double every count.

Raw events use `ON CONFLICT (event_id) DO NOTHING` — a repeated event is the
same measurement, so the first write wins. Curated windows use
`ON CONFLICT ... DO UPDATE` — a recomputed window is a *better* answer, because
late data may have arrived since, so the newer computation replaces the older.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from typing import Any, Iterable, Sequence

import psycopg

from common.db import execute_batched
from psycopg.rows import dict_row

from common.events import OCCUPANCY_PRECISION
from common.transforms import aqi_category, congestion_level
from common.validation import Rejected


@dataclass(slots=True)
class Ids:
    """Code-to-primary-key lookups, resolved once per run.

    The event stream carries business codes (`BLR-WHF`) while the tables use
    surrogate keys. Resolving per row would put a query in the hot path for no
    benefit — the mapping does not change during a batch.
    """

    zones: dict[str, int]
    cities: dict[str, int]
    sources: dict[str, int]

    @staticmethod
    def load(connection: psycopg.Connection) -> "Ids":
        with connection.cursor(row_factory=dict_row) as cursor:
            cursor.execute("SELECT id, code FROM zones WHERE deleted_at IS NULL")
            zones = {row["code"]: row["id"] for row in cursor.fetchall()}
            cursor.execute("SELECT id, slug FROM cities WHERE deleted_at IS NULL")
            cities = {row["slug"]: row["id"] for row in cursor.fetchall()}
            cursor.execute("SELECT id, code FROM data_sources WHERE deleted_at IS NULL")
            sources = {row["code"]: row["id"] for row in cursor.fetchall()}
        return Ids(zones=zones, cities=cities, sources=sources)


def _rows(payloads: Sequence[dict[str, Any]], builder) -> list[tuple]:
    return [builder(p) for p in payloads]


def write_traffic(connection: psycopg.Connection, payloads: Sequence[dict], ids: Ids) -> int:
    if not payloads:
        return 0

    def build(p: dict) -> tuple:
        # Rounded to the column's precision first, then labelled from that exact
        # value — the same treatment `category` gets in write_air_quality. The
        # producer's own label is validated as a legal enum but not stored: only
        # a value and a label derived from one another can be guaranteed to
        # agree, and at a band boundary they otherwise will not.
        occupancy = round(float(p["occupancy_ratio"]), OCCUPANCY_PRECISION)
        return (
            p["event_id"],
            ids.zones[p["zone_code"]],
            ids.sources[p["source_code"]],
            p["event_time"],
            p["vehicle_count"],
            p["average_speed_kph"],
            occupancy,
            str(congestion_level(occupancy)),
            p.get("demo_data", True),
        )

    rows = _rows(payloads, build)
    return execute_batched(
        connection,
        """
        INSERT INTO traffic_events
            (event_id, zone_id, source_id, event_time, vehicle_count,
             average_speed_kph, occupancy_ratio, congestion_level, demo_data)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (event_id) DO NOTHING
        """,
        rows,
    )


def write_weather(connection: psycopg.Connection, payloads: Sequence[dict], ids: Ids) -> int:
    if not payloads:
        return 0
    rows = _rows(payloads, lambda p: (
        p["event_id"],
        ids.cities[p["city_slug"]],
        ids.sources[p["source_code"]],
        p["event_time"],
        p["temperature_c"],
        p["humidity_pct"],
        p["precipitation_mm_h"],
        p["wind_speed_kph"],
        p.get("visibility_km"),
        p["condition"],
        p.get("demo_data", True),
    ))
    return execute_batched(
        connection,
        """
        INSERT INTO weather_events
            (event_id, city_id, source_id, event_time, temperature_c, humidity_pct,
             precipitation_mm_h, wind_speed_kph, visibility_km, condition, demo_data)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (event_id) DO NOTHING
        """,
        rows,
    )


def write_air_quality(connection: psycopg.Connection, payloads: Sequence[dict], ids: Ids) -> int:
    if not payloads:
        return 0
    # `category` is derived here rather than accepted from the producer, so the
    # band and the number can never disagree in storage.
    rows = _rows(payloads, lambda p: (
        p["event_id"],
        ids.zones[p["zone_code"]],
        ids.sources[p["source_code"]],
        p["event_time"],
        p["aqi"],
        p.get("pm25"), p.get("pm10"), p.get("no2"), p.get("o3"), p.get("co"),
        str(aqi_category(int(p["aqi"]))),
        p.get("demo_data", True),
    ))
    return execute_batched(
        connection,
        """
        INSERT INTO air_quality_events
            (event_id, zone_id, source_id, event_time, aqi,
             pm25, pm10, no2, o3, co, category, demo_data)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (event_id) DO NOTHING
        """,
        rows,
    )


def write_incidents(connection: psycopg.Connection, payloads: Sequence[dict], ids: Ids) -> int:
    """Upsert by (source, external_id).

    Incidents differ from readings: the same incident is reported repeatedly as
    it progresses from REPORTED to CLEARED. Inserting each report would produce
    a table of duplicates, so the feed's own identifier is the key and later
    reports update the row.
    """
    if not payloads:
        return 0
    from datetime import datetime as _dt

    def parse(value):
        return None if value is None else _dt.fromisoformat(str(value).replace("Z", "+00:00"))

    rows = _rows(payloads, lambda p: (
        ids.zones[p["zone_code"]],
        ids.sources[p["source_code"]],
        p["external_id"],
        p["incident_type"],
        p["severity"],
        p.get("status", "REPORTED"),
        p.get("description"),
        p["latitude"],
        p["longitude"],
        p.get("lanes_blocked"),
        parse(p["started_at"]),
        parse(p.get("resolved_at")),
        p.get("demo_data", True),
    ))
    return execute_batched(
        connection,
        """
        INSERT INTO incidents
            (uid, zone_id, source_id, external_id, incident_type, severity, status,
             description, latitude, longitude, lanes_blocked, started_at, resolved_at, demo_data)
        VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (source_id, external_id) WHERE external_id IS NOT NULL
        DO UPDATE SET
            status      = EXCLUDED.status,
            severity    = EXCLUDED.severity,
            resolved_at = EXCLUDED.resolved_at,
            updated_at  = now()
        """,
        rows,
    )


def write_city_events(connection: psycopg.Connection, payloads: Sequence[dict], ids: Ids) -> int:
    if not payloads:
        return 0
    from datetime import datetime as _dt

    def parse(value):
        return _dt.fromisoformat(str(value).replace("Z", "+00:00"))

    rows = _rows(payloads, lambda p: (
        ids.zones[p["zone_code"]],
        ids.sources[p["source_code"]],
        p["external_id"],
        p["event_category"],
        p["name"],
        p.get("venue"),
        p.get("expected_attendance"),
        parse(p["starts_at"]),
        parse(p["ends_at"]),
        p.get("status", "SCHEDULED"),
        p.get("demo_data", True),
    ))
    return execute_batched(
        connection,
        """
        INSERT INTO city_events
            (uid, zone_id, source_id, external_id, event_type, name, venue,
             expected_attendance, starts_at, ends_at, status, demo_data)
        VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
        ON CONFLICT (source_id, external_id) WHERE external_id IS NOT NULL
        DO UPDATE SET
            status     = EXCLUDED.status,
            starts_at  = EXCLUDED.starts_at,
            ends_at    = EXCLUDED.ends_at,
            updated_at = now()
        """,
        rows,
    )


# Column widths from V4__telemetry_schema.sql. Every value written to the DLQ is
# clamped to these.
#
# This matters more here than anywhere else in the loader: the DLQ *is* the error
# path. If writing a rejection can itself fail, one oversized field aborts the
# whole batch — including the valid records in it — at precisely the moment
# something had already gone wrong. A truncated diagnostic is always better than
# a failed insert.
_DLQ_TOPIC_MAX = 120
_DLQ_DETAIL_MAX = 500
_DLQ_PAYLOAD_MAX = 8192
_DLQ_EVENT_TYPE_MAX = 24


def write_dlq(
    connection: psycopg.Connection,
    rejections: Iterable[Rejected],
    ids: Ids,
    *,
    topic: str,
) -> int:
    """Persist rejected records with the reason they failed.

    Values are truncated rather than dropped: a 10 MB malformed blob should not
    become a 10 MB database row, but the first 8 KB is nearly always enough to
    see what went wrong.
    """
    # Kafka permits topic names up to 249 characters and a file source may pass
    # an arbitrarily long path, both of which overflow the column.
    safe_topic = topic[:_DLQ_TOPIC_MAX]

    rows = [
        (
            r.reason_code,
            (r.detail or "")[:_DLQ_DETAIL_MAX],
            r.raw_payload[:_DLQ_PAYLOAD_MAX] if r.raw_payload else None,
            (r.event_type or None) and r.event_type[:_DLQ_EVENT_TYPE_MAX],
            r.event_time,
            safe_topic,
        )
        for r in rejections
    ]
    if not rows:
        return 0
    return execute_batched(
        connection,
        """
        INSERT INTO ingestion_dlq
            (uid, reason_code, reason_detail, raw_payload, event_type, event_time, topic)
        VALUES (gen_random_uuid(), %s, %s, %s, %s, %s, %s)
        """,
        rows,
    )


def write_zone_metrics(connection: psycopg.Connection, windows: Sequence[dict]) -> int:
    """Upsert curated windows.

    DO UPDATE rather than DO NOTHING: recomputing a window means late data
    arrived, and the newer figure is the more complete one.
    """
    if not windows:
        return 0
    rows = [
        (
            w["zone_id"], w["window_start"], w["window_end"],
            w.get("vehicle_count"), w.get("average_speed_kph"), w.get("occupancy_ratio"),
            w.get("congestion_level"), w.get("aqi"), w.get("aqi_category"),
            w.get("temperature_c"), w.get("precipitation_mm_h"), w.get("weather_condition"),
            w.get("active_incidents", 0), w.get("active_events", 0),
            w.get("risk_score"), w.get("risk_level"),
            w.get("sample_count", 0), w.get("demo_data", True),
            w.get("aqi_source"),
            w.get("weather_source"),
            w.get("traffic_source"),
            # Always None out of the aggregator: a generated window describes
            # the road as occupancy and has no speed ratio. Written anyway, and
            # explicitly, because this statement also runs over windows a
            # previous overlay converted to the speed form. Omitting it would
            # leave that speed_ratio in place beside the occupancy restored on
            # the line above — one row claiming both metrics, which V22 exists
            # to prevent. The load runs before the overlay each hour, so the
            # real value is rewritten seconds later.
            w.get("speed_ratio"),
        )
        for w in windows
    ]
    return execute_batched(
        connection,
        """
        INSERT INTO zone_metrics
            (zone_id, window_start, window_end, vehicle_count, average_speed_kph,
             occupancy_ratio, congestion_level, aqi, aqi_category, temperature_c,
             precipitation_mm_h, weather_condition, active_incidents, active_events,
             risk_score, risk_level, sample_count, demo_data, aqi_source, weather_source,
             traffic_source, speed_ratio)
        VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                %s, %s)
        ON CONFLICT (zone_id, window_start, window_end) DO UPDATE SET
            vehicle_count      = EXCLUDED.vehicle_count,
            average_speed_kph  = EXCLUDED.average_speed_kph,
            occupancy_ratio    = EXCLUDED.occupancy_ratio,
            congestion_level   = EXCLUDED.congestion_level,
            aqi                = EXCLUDED.aqi,
            aqi_category       = EXCLUDED.aqi_category,
            temperature_c      = EXCLUDED.temperature_c,
            precipitation_mm_h = EXCLUDED.precipitation_mm_h,
            weather_condition  = EXCLUDED.weather_condition,
            active_incidents   = EXCLUDED.active_incidents,
            active_events      = EXCLUDED.active_events,
            risk_score         = EXCLUDED.risk_score,
            risk_level         = EXCLUDED.risk_level,
            sample_count       = EXCLUDED.sample_count,
            demo_data          = EXCLUDED.demo_data,
            aqi_source         = EXCLUDED.aqi_source,
            weather_source     = EXCLUDED.weather_source,
            traffic_source     = EXCLUDED.traffic_source,
            speed_ratio        = EXCLUDED.speed_ratio,
            computed_at        = now()
        """,
        rows,
    )


def write_quality_metrics(
    connection: psycopg.Connection,
    *,
    source_id: int | None,
    stage: str,
    window_start_at: datetime,
    window_end_at: datetime,
    received: int,
    valid: int,
    rejected: int,
    duplicate: int,
    late: int,
    max_lag_seconds: int | None,
) -> None:
    validity = (valid / received) if received else None
    with connection.cursor() as cursor:
        cursor.execute(
            """
            INSERT INTO data_quality_metrics
                (source_id, stage, window_start, window_end, records_received, records_valid,
                 records_rejected, records_duplicate, records_late, validity_ratio, max_lag_seconds)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (source_id, stage, window_start, window_end) DO UPDATE SET
                records_received  = EXCLUDED.records_received,
                records_valid     = EXCLUDED.records_valid,
                records_rejected  = EXCLUDED.records_rejected,
                records_duplicate = EXCLUDED.records_duplicate,
                records_late      = EXCLUDED.records_late,
                validity_ratio    = EXCLUDED.validity_ratio,
                max_lag_seconds   = EXCLUDED.max_lag_seconds,
                computed_at       = now()
            """,
            (source_id, stage, window_start_at, window_end_at, received, valid,
             rejected, duplicate, late, validity, max_lag_seconds),
        )


WRITERS = {
    "TRAFFIC": write_traffic,
    "WEATHER": write_weather,
    "AIR_QUALITY": write_air_quality,
    "INCIDENT": write_incidents,
    "CITY_EVENT": write_city_events,
}
