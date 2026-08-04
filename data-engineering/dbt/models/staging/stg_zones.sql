-- Zone reference, joined by nearly every downstream model.
with source as (
    select * from {{ source('citypulse', 'zones') }}
    where deleted_at is null
)
select
    id                as zone_id,
    uid              as zone_uid,
    city_id,
    code             as zone_code,
    name             as zone_name,
    zone_type,
    center_latitude::numeric(9,6)  as latitude,
    center_longitude::numeric(9,6) as longitude,
    area_sq_km,
    population,
    -- Zones without a recorded capacity would divide by zero downstream. The
    -- floor matches the generator's, so occupancy means the same thing on both
    -- sides of the pipeline.
    coalesce(road_capacity_vph, 1000) as road_capacity_vph,
    active            as is_active
from source
