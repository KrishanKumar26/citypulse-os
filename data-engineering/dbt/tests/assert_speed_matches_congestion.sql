-- Speed and congestion label must tell the same story.
--
-- Regression cover for a real defect: highway BPR coefficients left zones at
-- 83% occupancy reporting ~45 km/h while labelled HIGH, so a dashboard tile
-- would have read "HIGH congestion" beside a near-free-flow speed. Both values
-- are derived from the same occupancy figure, so any row where they disagree
-- means that derivation has drifted.
--
-- The bounds are deliberately loose — this catches contradiction, not
-- imprecision.
select
    traffic_event_id,
    zone_id,
    occupancy_ratio,
    average_speed_kph,
    congestion_level
from {{ ref('stg_traffic_events') }}
where
    (congestion_level = 'CRITICAL' and average_speed_kph > 32)
    or (congestion_level = 'HIGH' and average_speed_kph > 44)
    or (congestion_level = 'NORMAL' and average_speed_kph < 30)
