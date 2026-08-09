"""Point curated windows at the best air available for them, and say which it is.

The generated feeds travel event → window → `zone_metrics` in one pass, because
the aggregator sees them in the batch it is given. A real feed does not: WAQI
and Open-Meteo are fetched on their own schedule and written straight to
`air_quality_events`, so the curated windows covering them were already built,
from generated readings, before the real ones arrived. Written to the raw table
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
