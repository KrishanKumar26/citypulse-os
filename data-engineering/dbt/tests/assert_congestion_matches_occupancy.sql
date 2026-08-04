-- The label must match the band it was derived from (common/transforms.py).
-- A mismatch means the producer and the pipeline disagree on the thresholds.
select
    traffic_event_id,
    occupancy_ratio,
    congestion_level
from {{ ref('stg_traffic_events') }}
where congestion_level <> case
    when occupancy_ratio <= 0.55 then 'NORMAL'
    when occupancy_ratio <= 0.80 then 'MODERATE'
    when occupancy_ratio <= 1.00 then 'HIGH'
    else 'CRITICAL'
end
