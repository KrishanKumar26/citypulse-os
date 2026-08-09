-- =============================================================================
-- V21 — the second signal that stops being invented.
--
-- V19 gave air quality three provenances because two real feeds of different
-- kinds arrived. Weather now has one: Open-Meteo serves the same forecast model
-- that supplies the CAMS air data, for any coordinate, with no key. There is no
-- reason left for a city's temperature to be a number this repository made up.
--
-- Modelled, not measured, and for the same reason as CAMS: a numerical weather
-- model assimilates observations and then solves for a field. It tracks the real
-- atmosphere — Delhi at 27.6 °C and 91% humidity in a drizzle while Bengaluru
-- sits at 20.7 °C — and no instrument in this platform's inventory read it.
--
-- Traffic, incidents and city events stay generated. Not from lack of trying:
-- there is no free real-time feed for any of them in these cities, and the PRD
-- requires the platform to run without one. They keep saying so.
-- =============================================================================

INSERT INTO data_sources (uid, code, name, description, source_type,
                          ingestion_mode, status, config, demo_data, provenance,
                          attribution) VALUES
    (gen_random_uuid(),
     'open-meteo-weather',
     'Open-Meteo Forecast',
     -- description is VARCHAR(255). The reasoning lives in ingest/weather.py.
     'Modelled weather for each city centre: temperature, humidity, '
     || 'precipitation, wind and visibility, with the WMO code mapped to the '
     || 'platform''s own condition scale. Real atmosphere, solved for rather '
     || 'than measured.',
     'WEATHER',
     'REST_API',
     -- PAUSED until it delivers, like every other real feed here: a source
     -- marked ACTIVE with nothing behind it reads as a silent feed on the Data
     -- Health page, which is a fault report for a deliberate state.
     'PAUSED',
     '{"provider": "open-meteo.com", "model": "forecast", "requiresKey": false}'::jsonb,
     FALSE,
     'MODELLED',
     '[{"name": "Open-Meteo (CC BY 4.0)", "url": "https://open-meteo.com/"}]'::jsonb);

-- ---------------------------------------------------------------------------
-- Curated windows record where their weather came from too
--
-- Same shape and same reasoning as aqi_source in V19, including the way it is
-- added: a constant default is catalogue-only since PostgreSQL 11, so this does
-- not rewrite a table holding months of five-minute windows. The default is
-- then dropped, because the loader always states a provenance and a column that
-- silently supplied SYNTHETIC would hide a writer's bug behind this schema's
-- worst answer.
-- ---------------------------------------------------------------------------

ALTER TABLE zone_metrics ADD COLUMN weather_source VARCHAR(16) DEFAULT 'SYNTHETIC';
ALTER TABLE zone_metrics ALTER COLUMN weather_source DROP DEFAULT;

-- A window with no reading has no provenance. Cheaper in this direction:
-- nearly every window has weather.
UPDATE zone_metrics SET weather_source = NULL WHERE temperature_c IS NULL;

ALTER TABLE zone_metrics
    ADD CONSTRAINT ck_zone_metrics_weather_source
        CHECK (weather_source IS NULL
               OR weather_source IN ('MEASURED', 'MODELLED', 'SYNTHETIC'));

COMMENT ON COLUMN zone_metrics.weather_source IS
    'Where this window''s weather came from: MEASURED (an instrument), '
    'MODELLED (a numerical weather model), SYNTHETIC (generated). NULL when '
    'the window has no weather reading.';

CREATE INDEX idx_zone_metrics_weather_source
    ON zone_metrics (zone_id, window_start)
    WHERE weather_source IN ('MEASURED', 'MODELLED');
