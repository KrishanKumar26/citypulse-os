-- PRD §42: synthetic data must never be presented as real. Every row that came
-- from a SYNTHETIC source has to carry the flag all the way into the marts,
-- where the API reads it.
select zone_id, event_hour, demo_data
from {{ ref('fct_zone_conditions_hourly') }}
where demo_data is not true
