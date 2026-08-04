-- Hourly conditions per zone: the analytical fact table.
--
-- One row per zone per hour, carrying every signal side by side. This is the
-- grain the analytics module and the forecast feature pipeline both read, and
-- it is where the PRD's central claim becomes queryable — rain, events,
-- incidents and congestion sitting on one row is what lets a correlation be
-- measured rather than asserted (PRD §12).
--
-- Traffic drives the grain. A zone-hour with no traffic reading is not
-- reported: the platform did not measure that hour, and inventing a row for it
-- would put a gap in the data behind a number that looks like a measurement.

with traffic as (
    select * from {{ ref('int_zone_traffic_hourly') }}
),
air_quality as (
    select * from {{ ref('int_zone_air_quality_hourly') }}
),
weather as (
    select * from {{ ref('int_city_weather_hourly') }}
),
incidents as (
    select * from {{ ref('int_zone_incidents_hourly') }}
),
events as (
    select * from {{ ref('int_zone_events_hourly') }}
),
zones as (
    select * from {{ ref('stg_zones') }}
)

select
    z.zone_id,
    z.zone_code,
    z.zone_name,
    z.zone_type,
    z.city_id,
    t.event_hour,

    -- Traffic
    t.reading_count                                   as traffic_readings,
    t.vehicle_count,
    round(t.avg_speed_kph::numeric, 2)                as avg_speed_kph,
    round(t.min_speed_kph::numeric, 2)                as min_speed_kph,
    round(t.avg_occupancy_ratio::numeric, 4)          as avg_occupancy_ratio,
    round(t.peak_occupancy_ratio::numeric, 4)         as peak_occupancy_ratio,
    t.critical_readings,
    t.high_readings,
    -- Share of the hour spent degraded. More actionable than the mean, which
    -- hides a bad twenty minutes inside an otherwise ordinary hour.
    round(
        100.0 * (t.critical_readings + t.high_readings) / nullif(t.reading_count, 0),
        1
    )                                                 as pct_hour_congested,

    -- Air quality
    round(aq.avg_aqi::numeric, 0)                     as avg_aqi,
    aq.peak_aqi,
    round(aq.avg_pm25::numeric, 2)                    as avg_pm25,

    -- Weather, per city and attached to each of its zones
    round(w.avg_temperature_c::numeric, 2)            as avg_temperature_c,
    round(w.avg_humidity_pct::numeric, 2)             as avg_humidity_pct,
    round(w.peak_precipitation_mm_h::numeric, 2)      as peak_precipitation_mm_h,
    coalesce(w.had_rain, false)                       as had_rain,

    -- Context. Zero is a real measurement here — an hour with no incidents is
    -- a fact, unlike a missing air quality feed.
    coalesce(i.open_incidents, 0)                     as open_incidents,
    coalesce(i.severe_incidents, 0)                   as severe_incidents,
    coalesce(e.active_events, 0)                      as active_events,
    coalesce(e.expected_attendance, 0)                as expected_attendance,

    -- Calendar attributes the correlation and forecast layers group by.
    extract(dow from t.event_hour)::int               as day_of_week,
    extract(hour from t.event_hour)::int              as hour_of_day,
    (extract(dow from t.event_hour)::int in (0, 6))   as is_weekend,
    -- Peak windows in the seeded cities' local time. The events are stored in
    -- UTC and these cities are UTC+5:30, so the offset is applied here rather
    -- than leaving every consumer to rediscover it.
    (extract(hour from t.event_hour at time zone 'Asia/Kolkata')::int
        between 8 and 10)                             as is_morning_peak,
    (extract(hour from t.event_hour at time zone 'Asia/Kolkata')::int
        between 17 and 20)                            as is_evening_peak,

    round(t.avg_ingestion_lag_seconds::numeric, 1)    as avg_ingestion_lag_seconds,
    t.demo_data

from traffic t
join zones z
    on z.zone_id = t.zone_id
left join air_quality aq
    on aq.zone_id = t.zone_id and aq.event_hour = t.event_hour
left join weather w
    on w.city_id = z.city_id and w.event_hour = t.event_hour
left join incidents i
    on i.zone_id = t.zone_id and i.event_hour = t.event_hour
left join events e
    on e.zone_id = t.zone_id and e.event_hour = t.event_hour
