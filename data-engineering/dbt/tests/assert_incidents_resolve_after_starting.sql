select incident_id, started_at, resolved_at
from {{ ref('stg_incidents') }}
where resolved_at is not null and resolved_at < started_at
