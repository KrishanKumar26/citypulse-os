-- Daily zone summary, rolled up from the hourly fact.
--
-- Built from fct_zone_conditions_hourly rather than from raw events so the
-- daily and hourly views cannot disagree — one definition of a congested hour,
-- applied once.
select
    zone_id,
    zone_code,
    zone_name,
    zone_type,
    city_id,
    date_trunc('day', event_hour)                    as day,
    count(*)                                         as hours_observed,
    sum(vehicle_count)                               as vehicle_count,
    round(avg(avg_speed_kph), 2)                     as avg_speed_kph,
    round(min(min_speed_kph), 2)                     as worst_speed_kph,
    round(avg(avg_occupancy_ratio), 4)               as avg_occupancy_ratio,
    round(max(peak_occupancy_ratio), 4)              as peak_occupancy_ratio,
    round(avg(pct_hour_congested), 1)                as avg_pct_hour_congested,
    round(avg(avg_aqi), 0)                           as avg_aqi,
    max(peak_aqi)                                    as peak_aqi,
    round(avg(avg_temperature_c), 2)                 as avg_temperature_c,
    count(*) filter (where had_rain)                 as rainy_hours,
    max(open_incidents)                              as peak_open_incidents,
    sum(severe_incidents)                            as severe_incident_hours,
    max(active_events)                               as peak_active_events,
    demo_data
from {{ ref('fct_zone_conditions_hourly') }}
group by 1, 2, 3, 4, 5, 6, demo_data
