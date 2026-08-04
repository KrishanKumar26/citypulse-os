select
    zone_id,
    event_hour,
    count(*)      as reading_count,
    avg(aqi)      as avg_aqi,
    max(aqi)      as peak_aqi,
    avg(pm25)     as avg_pm25,
    avg(pm10)     as avg_pm10,
    bool_or(demo_data) as demo_data
from {{ ref('stg_air_quality_events') }}
group by 1, 2
