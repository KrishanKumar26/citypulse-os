with source as (
    select * from {{ source('citypulse', 'cities') }}
    where deleted_at is null
)
select
    id       as city_id,
    uid      as city_uid,
    slug     as city_slug,
    name     as city_name,
    country,
    timezone,
    population,
    area_sq_km,
    active   as is_active,
    demo_data
from source
