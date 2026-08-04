-- Incidents counted against every hour they were open, not only the hour they
-- started. An incident opening at 08:55 and clearing at 10:30 affected three
-- hours; attributing it to 08:00 alone would understate two of them.
with bounds as (
    select
        zone_id,
        incident_id,
        severity,
        date_trunc('hour', started_at) as first_hour,
        date_trunc('hour', coalesce(resolved_at, started_at)) as last_hour
    from {{ ref('stg_incidents') }}
),
expanded as (
    select
        b.zone_id,
        b.incident_id,
        b.severity,
        generate_series(b.first_hour, b.last_hour, interval '1 hour') as event_hour
    from bounds b
)
select
    zone_id,
    event_hour,
    count(distinct incident_id) as open_incidents,
    count(distinct incident_id) filter (
        where severity in ('HIGH', 'CRITICAL')
    ) as severe_incidents
from expanded
group by 1, 2
