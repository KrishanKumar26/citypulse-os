-- Hourly traffic per zone.
--
-- Ephemeral: this is inlined into the marts that use it. Nothing should query
-- an hourly rollup directly without the context the fact table adds.
select
    zone_id,
    event_hour,
    count(*)                            as reading_count,
    sum(vehicle_count)                  as vehicle_count,
    avg(average_speed_kph)              as avg_speed_kph,
    min(average_speed_kph)              as min_speed_kph,
    avg(occupancy_ratio)                as avg_occupancy_ratio,
    max(occupancy_ratio)                as peak_occupancy_ratio,
    -- Time spent in each state matters more than the hourly mean: an hour that
    -- averages MODERATE but spent twenty minutes CRITICAL is not a moderate hour.
    count(*) filter (where congestion_level = 'CRITICAL') as critical_readings,
    count(*) filter (where congestion_level = 'HIGH')     as high_readings,
    avg(ingestion_lag_seconds)          as avg_ingestion_lag_seconds,
    bool_or(demo_data)                  as demo_data
from {{ ref('stg_traffic_events') }}
group by 1, 2
