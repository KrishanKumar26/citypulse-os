select
    id                as traffic_event_id,
    event_id,
    zone_id,
    source_id,
    event_time,
    ingested_at,
    -- How long the platform took to store the reading. Charted as pipeline lag;
    -- a rising value means ingestion is falling behind the producers.
    extract(epoch from (ingested_at - event_time))::int as ingestion_lag_seconds,
    vehicle_count,
    average_speed_kph,
    occupancy_ratio,
    congestion_level,
    date_trunc('hour', event_time) as event_hour,
    demo_data
from {{ source('citypulse', 'traffic_events') }}
