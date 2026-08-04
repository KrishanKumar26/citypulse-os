-- Road capacity is the denominator of occupancy. A zero or negative value
-- would produce a division by zero or a negative congestion figure, so it is a
-- hard failure rather than a warning.
select zone_id, zone_code, road_capacity_vph
from {{ ref('stg_zones') }}
where road_capacity_vph is null or road_capacity_vph <= 0
