"""Point curated windows at the best reading available, and say which it is.

The generated feeds travel event → window → `zone_metrics` in one pass, because
the aggregator sees them in the batch it is given. A real feed does not: WAQI
and Open-Meteo are fetched on their own schedule and written straight to
`air_quality_events` and `weather_events`, so the curated windows covering them
were already built, from generated readings, before the real ones arrived.

Two signals now, air and weather, and both follow the rules below. Traffic,
incidents and city events have no real feed to overlay and are not handled
here. Written to the raw table
and left there, a real reading is something nothing reads — the dashboard reads
`zone_metrics`.

**Three provenances, ranked, never mixed.** An instrument beats a model and a
model beats the generator, and only the winning tier contributes to the number.
A mean of an instrument and a simulation is neither: it cannot be pointed at,
and it would carry the label of the better half. The ranking is by kind and not
by recency, so a station reading from the top of the hour is preferred to a CAMS
value from thirty minutes ago — the question a provenance answers is *what kind
of thing produced this*, and a fresher model output is still a model output.

**Readings carry forward, but not indefinitely.** Stations and CAMS both publish
hourly, while curated windows are five minutes wide, so a reading covers the
windows that follow it until `carry` elapses. Past that the window keeps its
generated AQI and says SYNTHETIC. That expiry is the point: a feed that stopped
three hours ago must stop colouring the present, and the failure mode of
carrying forever is a dashboard that reports an instrument's last word as though
the instrument were still speaking.

**Risk moves with the air.** Risk is derived from AQI among other things, so
replacing the AQI without recomputing risk would leave a window whose displayed
air and displayed risk disagree about what the air was.

Zones no real feed covers are untouched, keeping their generated AQI and their
SYNTHETIC label. With CAMS running that is nearly none of them; with only WAQI
it is most of them, because stations sit at fixed points and `ingest.waqi`
refuses to attribute one beyond its distance limit.
"""

from __future__ import annotations

from datetime import datetime, timedelta

import psycopg
from psycopg.rows import tuple_row

from common.transforms import aqi_category, risk_level, risk_score

#: The vocabulary, so callers reporting on a run name the same three states this
#: writes rather than repeating the strings.
MEASURED = "MEASURED"
MODELLED = "MODELLED"
SYNTHETIC = "SYNTHETIC"

#: How long one real reading speaks for the windows after it. Both feeds
#: publish hourly, so an hour covers the gap between readings without a window
#: ever being coloured by a feed that has gone quiet.
DEFAULT_CARRY = timedelta(hours=1)


def overlay(
    connection: psycopg.Connection,
    *,
    since: datetime | None = None,
    carry: timedelta = DEFAULT_CARRY,
) -> dict[str, int]:
    """Rewrite curated AQI, its band, its provenance and risk where a real
    reading covers the window.

    `since` bounds the curated windows considered; without one every window ever
    built is examined, which is correct and slow. Callers pass the range they
    just touched.

    Window boundaries are read from `zone_metrics` rather than recomputed from
    event times, so a change to the aggregator's window width cannot leave this
    matching readings against a grid the curated rows are not on.

    Returns a count per provenance written. Idempotent: a second run over the
    same readings computes the same numbers, finds nothing that differs, and
    writes nothing.
    """
    floor = None if since is None else since - carry

    # Its own row factory: callers reach this with whatever the connection was
    # opened with — the ingesters use dict rows — and positional unpacking
    # against a dict silently iterates its keys.
    with connection.cursor(row_factory=tuple_row) as cursor:
        cursor.execute(
            """
            WITH real_air AS (
                SELECT aq.zone_id, aq.event_time, aq.aqi, ds.provenance,
                       CASE ds.provenance WHEN 'MEASURED' THEN 1 ELSE 2 END AS tier
                  FROM air_quality_events aq
                  JOIN data_sources ds ON ds.id = aq.source_id
                 WHERE ds.provenance IN ('MEASURED', 'MODELLED')
                   AND (%(floor)s::timestamptz IS NULL
                        OR aq.event_time >= %(floor)s::timestamptz)
            )
            SELECT zm.zone_id, zm.window_start, c.aqi, c.provenance,
                   zm.occupancy_ratio, zm.active_incidents, zm.precipitation_mm_h
              FROM zone_metrics zm
              JOIN LATERAL (
                  -- The best tier that covers this window, and within it the
                  -- most recent moment. Averaged across whatever reported at
                  -- that moment, which is how two stations both inside a zone's
                  -- radius become one number for the zone.
                  SELECT r.provenance, round(avg(r.aqi))::int AS aqi
                    FROM real_air r
                   WHERE r.zone_id = zm.zone_id
                     AND r.event_time <  zm.window_end
                     AND r.event_time >= zm.window_end - %(carry)s::interval
                   GROUP BY r.tier, r.provenance, r.event_time
                   ORDER BY r.tier, r.event_time DESC
                   LIMIT 1
              ) c ON TRUE
             WHERE (%(since)s::timestamptz IS NULL
                    OR zm.window_start >= %(since)s::timestamptz)
               AND (zm.aqi        IS DISTINCT FROM c.aqi
                 OR zm.aqi_source IS DISTINCT FROM c.provenance)
            """,
            {"floor": floor, "since": since, "carry": carry},
        )
        targets = cursor.fetchall()

    if not targets:
        return {}

    counts: dict[str, int] = {}
    updates = []
    for zone_id, window_start, aqi, provenance, occupancy, incidents, precipitation in targets:
        score = risk_score(
            occupancy_ratio=float(occupancy) if occupancy is not None else None,
            aqi=aqi,
            active_incidents=incidents,
            precipitation_mm_h=float(precipitation) if precipitation is not None else None,
        )
        counts[provenance] = counts.get(provenance, 0) + 1
        updates.append((
            aqi, str(aqi_category(aqi)), score, risk_level(score), provenance,
            zone_id, window_start,
        ))

    with connection.cursor() as cursor:
        cursor.executemany(
            """
            UPDATE zone_metrics
               SET aqi          = %s,
                   aqi_category = %s,
                   risk_score   = %s,
                   risk_level   = %s,
                   aqi_source   = %s,
                   computed_at  = now()
             WHERE zone_id = %s AND window_start = %s
            """,
            updates,
        )
    connection.commit()
    return counts


def overlay_weather(
    connection: psycopg.Connection,
    *,
    since: datetime | None = None,
    carry: timedelta = DEFAULT_CARRY,
) -> dict[str, int]:
    """The same rewrite for weather, joined through the city rather than the zone.

    `weather_events.city_id`, not zone_id: a metro's zones share conditions
    closely enough that a per-zone temperature would be false precision, and the
    schema says so. So the covering reading is found for the zone's *city* and
    applied to every zone in it — which is exactly what the aggregator does with
    the generated feed.

    No averaging inside a tier here, unlike the air. A city has one weather
    reading at a moment; two would be the same provider answering twice.

    Risk moves with the rain. `risk_score` weighs precipitation, so replacing it
    without recomputing would leave a window whose displayed weather and
    displayed risk disagree about whether it was raining.
    """
    floor = None if since is None else since - carry

    with connection.cursor(row_factory=tuple_row) as cursor:
        cursor.execute(
            """
            WITH real_weather AS (
                SELECT we.city_id, we.event_time, we.temperature_c,
                       we.precipitation_mm_h, we.condition, ds.provenance,
                       CASE ds.provenance WHEN 'MEASURED' THEN 1 ELSE 2 END AS tier
                  FROM weather_events we
                  JOIN data_sources ds ON ds.id = we.source_id
                 WHERE ds.provenance IN ('MEASURED', 'MODELLED')
                   AND (%(floor)s::timestamptz IS NULL
                        OR we.event_time >= %(floor)s::timestamptz)
            )
            SELECT zm.zone_id, zm.window_start,
                   c.temperature_c, c.precipitation_mm_h, c.condition, c.provenance,
                   zm.occupancy_ratio, zm.aqi, zm.active_incidents
              FROM zone_metrics zm
              JOIN zones z ON z.id = zm.zone_id
              JOIN LATERAL (
                  SELECT r.provenance, r.temperature_c, r.precipitation_mm_h,
                         r.condition
                    FROM real_weather r
                   WHERE r.city_id = z.city_id
                     AND r.event_time <  zm.window_end
                     AND r.event_time >= zm.window_end - %(carry)s::interval
                   ORDER BY r.tier, r.event_time DESC
                   LIMIT 1
              ) c ON TRUE
             WHERE (%(since)s::timestamptz IS NULL
                    OR zm.window_start >= %(since)s::timestamptz)
               AND (zm.temperature_c      IS DISTINCT FROM c.temperature_c
                 OR zm.precipitation_mm_h IS DISTINCT FROM c.precipitation_mm_h
                 OR zm.weather_condition  IS DISTINCT FROM c.condition
                 OR zm.weather_source     IS DISTINCT FROM c.provenance)
            """,
            {"floor": floor, "since": since, "carry": carry},
        )
        targets = cursor.fetchall()

    if not targets:
        return {}

    counts: dict[str, int] = {}
    updates = []
    for (zone_id, window_start, temperature, precipitation, condition, provenance,
         occupancy, aqi, incidents) in targets:
        score = risk_score(
            occupancy_ratio=float(occupancy) if occupancy is not None else None,
            aqi=aqi,
            active_incidents=incidents,
            precipitation_mm_h=float(precipitation) if precipitation is not None else None,
        )
        counts[provenance] = counts.get(provenance, 0) + 1
        updates.append((
            temperature, precipitation, condition, provenance,
            score, risk_level(score), zone_id, window_start,
        ))

    with connection.cursor() as cursor:
        cursor.executemany(
            """
            UPDATE zone_metrics
               SET temperature_c      = %s,
                   precipitation_mm_h = %s,
                   weather_condition  = %s,
                   weather_source     = %s,
                   risk_score         = %s,
                   risk_level         = %s,
                   computed_at        = now()
             WHERE zone_id = %s AND window_start = %s
            """,
            updates,
        )
    connection.commit()
    return counts
