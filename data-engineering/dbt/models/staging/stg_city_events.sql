select
    id          as city_event_id,
    uid         as city_event_uid,
    external_id,
    zone_id,
    source_id,
    event_type  as event_category,
    name        as event_name,
    venue,
    expected_attendance,
    starts_at,
    ends_at,
    status,
    extract(epoch from (ends_at - starts_at))::int / 60 as duration_minutes,
    demo_data
from {{ source('citypulse', 'city_events') }}
