select
    id       as air_quality_event_id,
    event_id,
    zone_id,
    source_id,
    event_time,
    ingested_at,
    aqi,
    pm25,
    pm10,
    no2,
    o3,
    co,
    category as aqi_category,
    date_trunc('hour', event_time) as event_hour,
    demo_data
from {{ source('citypulse', 'air_quality_events') }}
