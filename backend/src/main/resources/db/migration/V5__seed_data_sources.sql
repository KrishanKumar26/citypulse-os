-- =============================================================================
-- V5 — Data sources for the synthetic pipeline (PRD §19, §43)
--
-- Five sources, one per event type, all SYNTHETIC and all demo_data = TRUE.
--
-- These are seeded rather than created by the generator at runtime so that the
-- foreign keys on the event tables have a target before a single event is
-- produced. It also means a source can be paused from the database to stop a
-- feed, without redeploying the generator.
--
-- `config` holds only non-secret shaping parameters. The generator reads them
-- so its behaviour is tunable without a code change; nothing here is a
-- credential (PRD §30).
-- =============================================================================

INSERT INTO data_sources (uid, code, name, description, source_type,
                          ingestion_mode, status, config, demo_data) VALUES
    (gen_random_uuid(),
     'synthetic-traffic',
     'Synthetic Traffic Generator',
     'Per-zone vehicle counts and speeds modelled on Indian metro weekday patterns.',
     'TRAFFIC', 'SYNTHETIC', 'ACTIVE',
     -- Two peaks a day, matching commute hours in the seeded cities' timezone.
     '{"emit_interval_seconds": 10,
       "morning_peak_hour": 9,
       "evening_peak_hour": 18,
       "peak_multiplier": 2.4,
       "night_multiplier": 0.18,
       "weekend_multiplier": 0.65,
       "free_flow_speed_kph": 48.0,
       "jam_speed_kph": 8.0}'::jsonb,
     TRUE),

    (gen_random_uuid(),
     'synthetic-weather',
     'Synthetic Weather Feed',
     'City-level temperature, humidity, wind and precipitation with monsoon seasonality.',
     'WEATHER', 'SYNTHETIC', 'ACTIVE',
     '{"emit_interval_seconds": 60,
       "base_temperature_c": 27.0,
       "diurnal_swing_c": 7.0,
       "rain_probability": 0.18,
       "heavy_rain_probability": 0.05}'::jsonb,
     TRUE),

    (gen_random_uuid(),
     'synthetic-air-quality',
     'Synthetic Air Quality Feed',
     'Per-zone AQI and pollutant concentrations correlated with traffic and rainfall.',
     'AIR_QUALITY', 'SYNTHETIC', 'ACTIVE',
     -- Rain scrubs particulates; the generator applies this factor so AQI and
     -- weather never contradict each other.
     '{"emit_interval_seconds": 60,
       "base_aqi": 95,
       "traffic_coupling": 0.55,
       "rain_washout_factor": 0.65,
       "industrial_zone_penalty": 35}'::jsonb,
     TRUE),

    (gen_random_uuid(),
     'synthetic-incidents',
     'Synthetic Incident Feed',
     'Accidents, breakdowns and closures, weighted by congestion and rainfall.',
     'INCIDENT', 'SYNTHETIC', 'ACTIVE',
     '{"base_hourly_rate_per_zone": 0.12,
       "congestion_multiplier": 3.0,
       "rain_multiplier": 2.2,
       "mean_duration_minutes": 42}'::jsonb,
     TRUE),

    (gen_random_uuid(),
     'synthetic-city-events',
     'Synthetic City Event Feed',
     'Scheduled sports, concerts and festivals used by the correlation engine.',
     'CITY_EVENT', 'SYNTHETIC', 'ACTIVE',
     '{"events_per_week_per_city": 4,
       "min_attendance": 2000,
       "max_attendance": 60000,
       "evening_start_bias": 0.7}'::jsonb,
     TRUE);
