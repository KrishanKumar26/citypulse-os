select
    id          as incident_id,
    uid         as incident_uid,
    external_id,
    zone_id,
    source_id,
    incident_type,
    severity,
    status,
    latitude,
    longitude,
    lanes_blocked,
    started_at,
    resolved_at,
    (resolved_at is null) as is_open,
    -- Null while open rather than measured against now(): a duration that grows
    -- every time the model runs is not a fact about the incident.
    case
        when resolved_at is not null
        then extract(epoch from (resolved_at - started_at))::int / 60
    end as duration_minutes,
    date_trunc('hour', started_at) as started_hour,
    demo_data
from {{ source('citypulse', 'incidents') }}
