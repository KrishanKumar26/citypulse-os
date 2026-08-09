"""Point curated windows at measured air where an instrument covered them.

The generated feeds travel event → window → `zone_metrics` in one pass, because
the aggregator sees them in the batch it is given. A real feed does not: CPCB
readings are fetched on their own schedule and written straight to
`air_quality_events`, so the curated windows covering them were already built,
from generated readings, before the real ones arrived.

Without this step the raw table holds a measurement nothing ever reads. The
dashboard reads `zone_metrics`, and `zone_metrics` would still be carrying the
number the generator invented for that zone and hour.

Two things this refuses to do:

**It does not average an instrument with a simulation.** Where a measurement
exists for a zone-window, it replaces the generated AQI outright. A mean of the
two is neither, and would be labelled measured on the strength of half of it.

**It does not leave the risk score behind.** Risk is derived from AQI among
other things, so changing the AQI without recomputing risk would leave a window
whose displayed air and displayed risk disagree about what the air was.

Zones no station covers are untouched, keeping their generated AQI and their
`aqi_measured = FALSE`. That is the honest result: CPCB stations sit at fixed
points, `ingest.cpcb` refuses to attribute one beyond MAX_STATION_KM, and a
platform that showed every zone as measured because a few were would be lying
about most of them.
"""

from __future__ import annotations

from datetime import datetime, timedelta

import psycopg
from psycopg.rows import tuple_row

from common.transforms import aqi_category, risk_level, risk_score
from pipeline.aggregate import DEFAULT_WINDOW


def overlay(
    connection: psycopg.Connection,
    *,
    since: datetime | None = None,
    window: timedelta = DEFAULT_WINDOW,
) -> int:
    """Rewrite curated AQI, its band and risk wherever a measurement covers it.

    Returns the number of windows changed. Idempotent: running it twice over the
    same readings computes the same numbers and writes them again.
    """
    seconds = int(window.total_seconds())

    # Its own row factory: callers reach this with whatever the connection was
    # opened with — generator.catalog hands out dict rows — and positional
    # unpacking against a dict silently iterates its keys.
    with connection.cursor(row_factory=tuple_row) as cursor:
        cursor.execute(
            """
            WITH measured AS (
                SELECT aq.zone_id,
                       to_timestamp(
                           floor(extract(epoch FROM aq.event_time) / %(secs)s) * %(secs)s
                       ) AS window_start,
                       round(avg(aq.aqi))::int AS aqi
                  FROM air_quality_events aq
                 WHERE aq.demo_data = FALSE
                   AND (%(since)s::timestamptz IS NULL OR aq.event_time >= %(since)s)
                 GROUP BY 1, 2
            )
            SELECT zm.zone_id, zm.window_start, m.aqi,
                   zm.occupancy_ratio, zm.active_incidents, zm.precipitation_mm_h
              FROM zone_metrics zm
              JOIN measured m
                ON m.zone_id = zm.zone_id
               AND m.window_start = zm.window_start
             WHERE zm.aqi IS DISTINCT FROM m.aqi
                OR zm.aqi_measured IS DISTINCT FROM TRUE
            """,
            {"secs": seconds, "since": since},
        )
        targets = cursor.fetchall()

    if not targets:
        return 0

    updates = []
    for zone_id, window_start, aqi, occupancy, incidents, precipitation in targets:
        score = risk_score(
            occupancy_ratio=float(occupancy) if occupancy is not None else None,
            aqi=aqi,
            active_incidents=incidents,
            precipitation_mm_h=float(precipitation) if precipitation is not None else None,
        )
        updates.append((
            aqi, str(aqi_category(aqi)), score, risk_level(score), zone_id, window_start,
        ))

    with connection.cursor() as cursor:
        cursor.executemany(
            """
            UPDATE zone_metrics
               SET aqi          = %s,
                   aqi_category = %s,
                   risk_score   = %s,
                   risk_level   = %s,
                   aqi_measured = TRUE,
                   computed_at  = now()
             WHERE zone_id = %s AND window_start = %s
            """,
            updates,
        )
    connection.commit()
    return len(updates)
