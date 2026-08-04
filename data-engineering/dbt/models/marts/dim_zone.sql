-- Zone dimension: geography joined to its city, with the descriptive columns
-- every mart and every API response needs.
select
    z.zone_id,
    z.zone_uid,
    z.zone_code,
    z.zone_name,
    z.zone_type,
    z.latitude,
    z.longitude,
    z.area_sq_km,
    z.population,
    z.road_capacity_vph,
    c.city_id,
    c.city_slug,
    c.city_name,
    c.country,
    c.timezone,
    z.is_active,
    c.demo_data
from {{ ref('stg_zones') }} z
join {{ ref('stg_cities') }} c using (city_id)
