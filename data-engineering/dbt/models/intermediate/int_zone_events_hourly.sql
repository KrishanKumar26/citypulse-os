-- Same treatment for scheduled events: a four-hour concert affects four hours.
with expanded as (
    select
        zone_id,
        city_event_id,
        expected_attendance,
        generate_series(
            date_trunc('hour', starts_at),
            date_trunc('hour', ends_at),
            interval '1 hour'
        ) as event_hour
    from {{ ref('stg_city_events') }}
    where status <> 'CANCELLED'
)
select
    zone_id,
    event_hour,
    count(distinct city_event_id)   as active_events,
    sum(expected_attendance)        as expected_attendance
from expanded
group by 1, 2
