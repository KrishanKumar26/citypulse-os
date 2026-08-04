-- The AQI band is derived by the loader precisely so the label and the number
-- cannot disagree. This asserts that guarantee actually held.
select
    air_quality_event_id,
    aqi,
    aqi_category
from {{ ref('stg_air_quality_events') }}
where aqi_category <> case
    when aqi <= 50 then 'GOOD'
    when aqi <= 100 then 'SATISFACTORY'
    when aqi <= 200 then 'MODERATE'
    when aqi <= 300 then 'POOR'
    when aqi <= 400 then 'VERY_POOR'
    else 'SEVERE'
end
