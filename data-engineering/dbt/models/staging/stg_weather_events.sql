select
    id       as weather_event_id,
    event_id,
    city_id,
    source_id,
    event_time,
    ingested_at,
    temperature_c,
    humidity_pct,
    precipitation_mm_h,
    wind_speed_kph,
    visibility_km,
    condition as weather_condition,
    -- Derived once here rather than repeated in every model that asks
    -- "was it raining?".
    (precipitation_mm_h > 0) as is_raining,
    date_trunc('hour', event_time) as event_hour,
    demo_data
from {{ source('citypulse', 'weather_events') }}
