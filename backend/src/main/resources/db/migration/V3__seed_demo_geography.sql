-- =============================================================================
-- V3 — Demo geography: three cities and their monitored zones.
--
-- Every row is flagged demo_data = TRUE. The flag is carried through the API to
-- the UI, which labels the data as simulated (PRD §42, §31 of the execution
-- prompt). Synthetic data is never presented as live real-world information.
--
-- This lives in the main migration path rather than a dev-only seed because
-- demo mode is a product requirement, not a development convenience. Real
-- cities added later carry demo_data = FALSE.
--
-- Zone road_capacity_vph values are the vehicles-per-hour the zone's network
-- carries before congestion sets in. They are the denominator for congestion
-- percentage, so the synthetic generator and the forecast baseline both depend
-- on them being plausible for the zone type and size.
-- =============================================================================

INSERT INTO cities (uid, slug, name, country, country_code, timezone,
                    center_latitude, center_longitude, default_zoom,
                    population, area_sq_km, active, demo_data) VALUES
    (gen_random_uuid(), 'bengaluru', 'Bengaluru', 'India', 'IN', 'Asia/Kolkata',
     12.971600, 77.594600, 11, 13608000, 741.00, TRUE, TRUE),
    (gen_random_uuid(), 'noida', 'Noida', 'India', 'IN', 'Asia/Kolkata',
     28.535500, 77.391000, 12, 1200000, 203.00, TRUE, TRUE),
    (gen_random_uuid(), 'mumbai', 'Mumbai', 'India', 'IN', 'Asia/Kolkata',
     19.076000, 72.877700, 11, 21296000, 603.40, TRUE, TRUE);

-- --------------------------------------------------------------------------
-- Bengaluru
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('BLR-WHF', 'Whitefield',        'COMMERCIAL',  12.969800, 77.750000, 24.50, 250000, 9200),
    ('BLR-KOR', 'Koramangala',       'MIXED',       12.935200, 77.624500, 11.20, 180000, 7400),
    ('BLR-ELC', 'Electronic City',   'INDUSTRIAL',  12.845200, 77.660200, 33.80, 210000, 10500),
    ('BLR-IND', 'Indiranagar',       'COMMERCIAL',  12.978400, 77.640800,  8.60, 140000, 6100),
    ('BLR-MGR', 'MG Road Central',   'COMMERCIAL',  12.975000, 77.606000,  5.40,  95000, 8300),
    ('BLR-HBL', 'Hebbal',            'TRANSIT_HUB', 13.035800, 77.597000, 14.70, 160000, 11800),
    ('BLR-JYN', 'Jayanagar',         'RESIDENTIAL', 12.925000, 77.593800, 12.90, 205000, 5600),
    ('BLR-KIA', 'Kempegowda Airport','AIRPORT',     13.198600, 77.706600, 40.20,  12000, 4800)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'bengaluru';

-- --------------------------------------------------------------------------
-- Noida — includes Sector 18, used as the worked example throughout the PRD
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('NOI-S18', 'Sector 18',         'COMMERCIAL',  28.570000, 77.321000,  4.20,  48000, 8000),
    ('NOI-S62', 'Sector 62',         'COMMERCIAL',  28.627000, 77.373000,  6.80,  72000, 7200),
    ('NOI-S137','Sector 137',        'RESIDENTIAL', 28.498000, 77.418000,  9.10, 110000, 5200),
    ('NOI-BGD', 'Botanical Garden',  'TRANSIT_HUB', 28.564000, 77.334000,  3.10,  26000, 9600),
    ('NOI-S15', 'Sector 15',         'RESIDENTIAL', 28.586000, 77.311000,  3.60,  41000, 4300),
    ('NOI-S128','Sector 128',        'MIXED',       28.515000, 77.363000,  7.40,  63000, 6400)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'noida';

-- --------------------------------------------------------------------------
-- Mumbai
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('MUM-BKC', 'Bandra Kurla Complex', 'COMMERCIAL',  19.066200, 72.869700,  3.70,  35000, 10200),
    ('MUM-ADE', 'Andheri East',         'MIXED',       19.113600, 72.869700, 18.40, 420000,  9400),
    ('MUM-LPL', 'Lower Parel',          'COMMERCIAL',  18.996000, 72.825800,  6.20, 155000,  7800),
    ('MUM-PWI', 'Powai',                'RESIDENTIAL', 19.117600, 72.906000,  9.80, 190000,  5900),
    ('MUM-CST', 'CSMT Precinct',        'TRANSIT_HUB', 18.939800, 72.835500,  2.90,  48000, 12400),
    ('MUM-BOM', 'CSMI Airport',         'AIRPORT',     19.089600, 72.865600, 12.60,  15000,  5100)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'mumbai';
