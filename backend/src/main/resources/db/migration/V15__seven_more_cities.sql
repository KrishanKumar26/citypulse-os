-- =============================================================================
-- V15 — Seven more Indian metros, bringing the platform to ten cities.
--
-- Same treatment as V3: real places, real coordinates, real zone names, and
-- demo_data = TRUE on every row because the *readings* are still generated. The
-- geography is genuine and the telemetry is not, and the flag is what keeps
-- those two facts from being confused (PRD §42).
--
-- Coordinates are the recognised centre of each area, and zone types follow what
-- the area actually is — an airport zone is an AIRPORT, a business district is
-- COMMERCIAL. This matters beyond labelling: the generator shapes traffic by
-- zone type, so mislabelling an industrial belt as residential would produce a
-- plausible-looking city that behaves wrongly at every hour of the day.
--
-- road_capacity_vph is the vehicles per hour the zone's network carries before
-- congestion sets in. It is the denominator for every congestion percentage on
-- the dashboard, so the figures are scaled to the zone's size and character
-- rather than copied between cities.
-- =============================================================================

INSERT INTO cities (uid, slug, name, country, country_code, timezone,
                    center_latitude, center_longitude, default_zoom,
                    population, area_sq_km, active, demo_data) VALUES
    (gen_random_uuid(), 'delhi', 'Delhi', 'India', 'IN', 'Asia/Kolkata',
     28.613900, 77.209000, 11, 32941000, 1484.00, TRUE, TRUE),
    (gen_random_uuid(), 'hyderabad', 'Hyderabad', 'India', 'IN', 'Asia/Kolkata',
     17.385000, 78.486700, 11, 10801000, 650.00, TRUE, TRUE),
    (gen_random_uuid(), 'chennai', 'Chennai', 'India', 'IN', 'Asia/Kolkata',
     13.082700, 80.270700, 11, 11503000, 426.00, TRUE, TRUE),
    (gen_random_uuid(), 'kolkata', 'Kolkata', 'India', 'IN', 'Asia/Kolkata',
     22.572600, 88.363900, 11, 15134000, 206.08, TRUE, TRUE),
    (gen_random_uuid(), 'pune', 'Pune', 'India', 'IN', 'Asia/Kolkata',
     18.520400, 73.856700, 11, 7166000, 331.26, TRUE, TRUE),
    (gen_random_uuid(), 'ahmedabad', 'Ahmedabad', 'India', 'IN', 'Asia/Kolkata',
     23.022500, 72.571400, 11, 8450000, 505.00, TRUE, TRUE),
    (gen_random_uuid(), 'jaipur', 'Jaipur', 'India', 'IN', 'Asia/Kolkata',
     26.912400, 75.787300, 11, 4067000, 467.00, TRUE, TRUE);

-- --------------------------------------------------------------------------
-- Delhi
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('DEL-CNP', 'Connaught Place',   'COMMERCIAL',  28.632800, 77.219700,  4.20,  42000, 9800),
    ('DEL-SKT', 'Saket',             'MIXED',       28.522500, 77.209100, 10.60, 180000, 7200),
    ('DEL-DWK', 'Dwarka',            'RESIDENTIAL', 28.592300, 77.046000, 56.50, 1100000, 8600),
    ('DEL-IGI', 'Indira Gandhi Airport', 'AIRPORT', 28.556500, 77.100000, 20.30,   9000, 5200),
    ('DEL-NZM', 'Nizamuddin',        'TRANSIT_HUB', 28.588600, 77.251000,  6.80,  95000, 10400),
    ('DEL-OKH', 'Okhla Industrial',  'INDUSTRIAL',  28.530200, 77.271400, 18.90, 210000, 8900)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'delhi';

-- --------------------------------------------------------------------------
-- Hyderabad
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('HYD-HTC', 'HITEC City',        'COMMERCIAL',  17.446000, 78.349800, 12.40, 165000, 9600),
    ('HYD-GCH', 'Gachibowli',        'MIXED',       17.440300, 78.348900, 14.10, 140000, 8200),
    ('HYD-BJH', 'Banjara Hills',     'MIXED',       17.412600, 78.438700,  9.30, 120000, 6800),
    ('HYD-SEC', 'Secunderabad',      'TRANSIT_HUB', 17.439900, 78.498300,  8.70, 205000, 10100),
    ('HYD-CHR', 'Charminar',         'COMMERCIAL',  17.361600, 78.474700,  3.90,  88000, 4200),
    ('HYD-RGI', 'Rajiv Gandhi Airport', 'AIRPORT',  17.240400, 78.429400, 22.60,   7000, 4600)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'hyderabad';

-- --------------------------------------------------------------------------
-- Chennai
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('MAA-TNR', 'T. Nagar',          'COMMERCIAL',  13.040500, 80.233500,  5.10, 190000, 7100),
    ('MAA-OMR', 'OMR IT Corridor',   'COMMERCIAL',  12.887400, 80.227900, 28.40, 220000, 11200),
    ('MAA-GDY', 'Guindy',            'INDUSTRIAL',  13.010200, 80.220600, 11.60, 145000, 9400),
    ('MAA-ADY', 'Adyar',             'RESIDENTIAL', 13.006700, 80.257200,  8.20, 135000, 5400),
    ('MAA-CEN', 'Chennai Central',   'TRANSIT_HUB', 13.081900, 80.275300,  4.60,  72000, 10600),
    ('MAA-MAA', 'Chennai Airport',   'AIRPORT',     12.994100, 80.180700, 13.70,   6500, 4400)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'chennai';

-- --------------------------------------------------------------------------
-- Kolkata
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('CCU-PKS', 'Park Street',       'COMMERCIAL',  22.552800, 88.352000,  3.40,  68000, 6900),
    ('CCU-SLT', 'Salt Lake Sector V','COMMERCIAL',  22.577700, 88.433100, 10.80, 125000, 8800),
    ('CCU-HWH', 'Howrah Bridge',     'TRANSIT_HUB', 22.585400, 88.346800,  2.10,  45000, 12400),
    ('CCU-BBD', 'BBD Bagh',          'COMMERCIAL',  22.569300, 88.348800,  2.80,  52000, 7300),
    ('CCU-BHN', 'Behala',            'RESIDENTIAL', 22.499200, 88.318600, 15.60, 240000, 5100),
    ('CCU-CCU', 'Netaji Subhas Airport', 'AIRPORT', 22.654100, 88.446700, 10.90,   5500, 4100)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'kolkata';

-- --------------------------------------------------------------------------
-- Pune
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('PNQ-HJW', 'Hinjawadi IT Park', 'COMMERCIAL',  18.591300, 73.738900, 22.70, 175000, 10200),
    ('PNQ-KRG', 'Koregaon Park',     'MIXED',       18.536200, 73.893200,  6.40,  72000, 5800),
    ('PNQ-SWG', 'Shivajinagar',      'TRANSIT_HUB', 18.530800, 73.850200,  5.90, 110000, 9100),
    ('PNQ-KHD', 'Kharadi',           'COMMERCIAL',  18.551900, 73.947600, 12.30,  95000, 7600),
    ('PNQ-PMP', 'Pimpri-Chinchwad',  'INDUSTRIAL',  18.627900, 73.809900, 30.10, 380000, 9700),
    ('PNQ-PNQ', 'Pune Airport',      'AIRPORT',     18.582700, 73.919700,  8.40,   4200, 3900)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'pune';

-- --------------------------------------------------------------------------
-- Ahmedabad
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('AMD-SGR', 'SG Highway',        'COMMERCIAL',  23.028300, 72.507500, 18.60, 145000, 11600),
    ('AMD-NVR', 'Navrangpura',       'MIXED',       23.036200, 72.560900,  6.70,  98000, 6400),
    ('AMD-MNK', 'Maninagar',         'RESIDENTIAL', 22.997900, 72.601300, 12.40, 210000, 5300),
    ('AMD-SBM', 'Sabarmati Riverfront','MIXED',     23.058000, 72.580200,  9.10,  76000, 7000),
    ('AMD-NRD', 'Naroda Industrial', 'INDUSTRIAL',  23.070400, 72.657100, 21.80, 165000, 8400),
    ('AMD-AMD', 'Ahmedabad Airport', 'AIRPORT',     23.077200, 72.634700,  9.60,   4800, 3700)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'ahmedabad';

-- --------------------------------------------------------------------------
-- Jaipur
-- --------------------------------------------------------------------------
INSERT INTO zones (uid, city_id, code, name, zone_type,
                   center_latitude, center_longitude, area_sq_km, population,
                   road_capacity_vph, active)
SELECT gen_random_uuid(), c.id, z.code, z.name, z.zone_type,
       z.lat, z.lon, z.area, z.pop, z.capacity, TRUE
FROM cities c
JOIN (VALUES
    ('JAI-MIR', 'MI Road',           'COMMERCIAL',  26.916200, 75.812000,  4.30,  62000, 6600),
    ('JAI-CWL', 'C-Scheme',          'MIXED',       26.907800, 75.798900,  5.80,  71000, 5900),
    ('JAI-MSR', 'Malviya Nagar',     'RESIDENTIAL', 26.854600, 75.815500, 11.20, 158000, 5200),
    ('JAI-VKI', 'Vishwakarma Industrial','INDUSTRIAL', 26.978300, 75.784200, 19.40, 132000, 7800),
    ('JAI-PNK', 'Pink City Bazaar',  'COMMERCIAL',  26.923000, 75.826700,  3.10,  55000, 3800),
    ('JAI-JAI', 'Jaipur Airport',    'AIRPORT',     26.824200, 75.812200,  7.90,   3600, 3400)
) AS z(code, name, zone_type, lat, lon, area, pop, capacity) ON TRUE
WHERE c.slug = 'jaipur';
