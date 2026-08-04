-- Nothing may be stored with a timestamp meaningfully ahead of its ingestion.
-- The validator rejects these to the DLQ; this proves none slipped past it.
select traffic_event_id, event_time, ingested_at
from {{ ref('stg_traffic_events') }}
where event_time > ingested_at + interval '10 minutes'
