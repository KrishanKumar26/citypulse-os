select
    city_id,
    event_hour,
    count(*)                   as reading_count,
    avg(temperature_c)         as avg_temperature_c,
    avg(humidity_pct)          as avg_humidity_pct,
    sum(precipitation_mm_h)    as total_precipitation_mm,
    max(precipitation_mm_h)    as peak_precipitation_mm_h,
    avg(wind_speed_kph)        as avg_wind_speed_kph,
    bool_or(is_raining)        as had_rain,
    bool_or(demo_data)         as demo_data
from {{ ref('stg_weather_events') }}
group by 1, 2
