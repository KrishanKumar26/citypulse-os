-- =============================================================================
-- V19 — three provenances for air, not two, and the attribution that comes with
--       the feeds that supply them.
--
-- V18 asked one question of every curated window: was this AQI measured? That
-- was the right question while the only candidate real feed was CPCB, whose
-- rows are instrument readings or nothing. It stops being answerable once a
-- reanalysis feed arrives.
--
-- Open-Meteo serves the Copernicus CAMS model. CAMS assimilates satellite
-- retrievals and ground stations and then *solves* for a concentration field,
-- so it produces a number for any coordinate on earth — including the fifty-odd
-- zones here that no station is within eight kilometres of. That number is
-- worth far more than the generator's invention: it moves with the real
-- atmosphere, and it puts Delhi at 192 while Bengaluru sits at 29 because that
-- is what the air did today.
--
-- It is still not a measurement. No instrument stood in that zone. Recording it
-- as measured would be the exact failure this schema exists to prevent, and
-- recording it as synthetic would throw away the distinction between a model of
-- the real atmosphere and a random walk. So there are three states:
--
--   MEASURED   an instrument reported it — CPCB stations, via WAQI
--   MODELLED   a physical model of the real atmosphere produced it — CAMS
--   SYNTHETIC  this platform's generator invented it
--   NULL       the window has no AQI at all; "not measured" is not "synthetic"
--
-- Provenance lives on the source, not on the reading. Every row in
-- air_quality_events already points at the data_source it arrived through, and
-- a source's kind does not vary row by row: CPCB cannot emit a modelled reading
-- and CAMS cannot emit a measured one. Putting it on the reading would let the
-- two disagree and give no answer for which is right.
--
-- ATTRIBUTION
--
-- WAQI's terms require it: "Attribution to the World Air Quality Index Project
-- as well as originating EPA is mandatory." The originating EPA differs per
-- station and is returned per response, so it cannot be a constant in a
-- migration — the column holds what the feed actually said about the readings
-- this deployment holds, and the ingester rewrites it each run.
-- =============================================================================

-- ---------------------------------------------------------------------------
-- Sources carry their provenance and their credits
-- ---------------------------------------------------------------------------

ALTER TABLE data_sources
    ADD COLUMN provenance  VARCHAR(16) NOT NULL DEFAULT 'SYNTHETIC',
    ADD COLUMN attribution JSONB       NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE data_sources
    ADD CONSTRAINT ck_data_sources_provenance
        CHECK (provenance IN ('MEASURED', 'MODELLED', 'SYNTHETIC'));

COMMENT ON COLUMN data_sources.provenance IS
    'MEASURED = an instrument reported it; MODELLED = a physical model of the '
    'real atmosphere produced it; SYNTHETIC = this platform generated it.';

COMMENT ON COLUMN data_sources.attribution IS
    'Credits this feed requires, as [{"name":..., "url":...}]. Written by the '
    'ingester from what the provider returned for the readings actually held, '
    'never hardcoded — the originating agency differs per station.';

-- The five generated feeds keep the default. CPCB is the one existing source
-- that is not this platform talking to itself.
UPDATE data_sources SET provenance = 'MEASURED' WHERE code = 'cpcb-air-quality';

-- ---------------------------------------------------------------------------
-- Two new feeds
--
-- Both PAUSED, for the reason V16 gives: a source marked ACTIVE while nothing
-- feeds it appears on Data Health as a silent feed, which is a fault report for
-- a deliberate state. Each ingester flips its own source to ACTIVE on the run
-- that first delivers, so the status describes this deployment rather than the
-- repository's hopes for it.
--
-- Open-Meteo is PAUSED too even though it needs no key: a fork that never runs
-- the job should not show a feed that has delivered nothing as healthy.
-- ---------------------------------------------------------------------------

INSERT INTO data_sources (uid, code, name, description, source_type,
                          ingestion_mode, status, config, demo_data, provenance) VALUES
    (gen_random_uuid(),
     'waqi-air-quality',
     'WAQI Ground Stations',
     -- description is VARCHAR(255). The reasoning lives in ingest/waqi.py.
     'Government monitoring stations via the World Air Quality Index project — '
     || 'in India, CPCB''s own instruments republished with a working API. A '
     || 'station is attributed to a zone only when it is within 8 km of the '
     || 'zone centre.',
     'AIR_QUALITY',
     'REST_API',
     'PAUSED',
     '{"provider": "aqicn.org", "maxStationKm": 8.0, "bounds": "6,68,36,98"}'::jsonb,
     FALSE,
     'MEASURED'),

    (gen_random_uuid(),
     'open-meteo-cams',
     'Copernicus CAMS via Open-Meteo',
     'Modelled air quality for every zone centre, from the Copernicus '
     || 'Atmosphere Monitoring Service. Not a measurement — no instrument stood '
     || 'in the zone — but a physical model of the real atmosphere, so it '
     || 'tracks what the air actually did.',
     'AIR_QUALITY',
     'REST_API',
     'PAUSED',
     '{"provider": "open-meteo.com", "model": "CAMS", "requiresKey": false}'::jsonb,
     FALSE,
     'MODELLED');

-- Open-Meteo asks for attribution and its licence is fixed, so unlike WAQI's
-- per-station credits this one is knowable here.
UPDATE data_sources
   SET attribution = '[{"name": "Open-Meteo (CC BY 4.0)", "url": "https://open-meteo.com/"},
                       {"name": "Copernicus Atmosphere Monitoring Service", "url": "https://atmosphere.copernicus.eu/"}]'::jsonb
 WHERE code = 'open-meteo-cams';

-- The project credit is required of every consumer whatever the station, so it
-- is known here; `ingest.waqi` adds the originating agency for each station it
-- actually used, which is not.
UPDATE data_sources
   SET attribution = '[{"name": "World Air Quality Index Project", "url": "https://waqi.info/"}]'::jsonb
 WHERE code = 'waqi-air-quality';

-- data.gov.in publishes under the National Data Sharing and Accessibility
-- Policy, which requires the source be credited.
UPDATE data_sources
   SET attribution = '[{"name": "Central Pollution Control Board", "url": "https://cpcb.nic.in/"},
                       {"name": "data.gov.in (NDSAP)", "url": "https://data.gov.in/"}]'::jsonb
 WHERE code = 'cpcb-air-quality';

-- ---------------------------------------------------------------------------
-- Curated windows record which of the three they got
--
-- aqi_measured is dropped rather than kept alongside. Two columns that must
-- agree eventually will not, and there would be no rule for which one the
-- dashboard should believe. It shipped one day ago to a demo deployment and to
-- no other consumer, so the cost of removing it now is a rename in three files
-- against a permanent ambiguity in the schema.
-- ---------------------------------------------------------------------------

DROP INDEX IF EXISTS idx_zone_metrics_aqi_measured;

-- Added WITH a default and then stripped of it, which is not a detour.
--
-- `zone_metrics` is the largest table here — the deployed demo holds months of
-- five-minute windows for sixty-two zones — and the obvious form of this
-- migration, add a nullable column and UPDATE every row, rewrites all of them.
-- On a free-tier database that is minutes of held table lock during a deploy,
-- and a container whose health check fails mid-migration would roll it back and
-- start again.
--
-- Since PostgreSQL 11, ADD COLUMN with a constant default does not rewrite: the
-- value is recorded once in the catalogue and existing rows read it without
-- being touched. DROP DEFAULT then removes it for *future* inserts while
-- leaving what existing rows resolve to. The two UPDATEs below are what remain,
-- and both touch a handful of rows rather than all of them.
--
-- The default has to go. The loader always states a provenance, and a column
-- that quietly supplied SYNTHETIC for an omitted one would put this schema's
-- worst answer behind a writer's bug.
ALTER TABLE zone_metrics ADD COLUMN aqi_source VARCHAR(16) DEFAULT 'SYNTHETIC';
ALTER TABLE zone_metrics ALTER COLUMN aqi_source DROP DEFAULT;

-- Carried over rather than recomputed: these windows already recorded the
-- answer as a boolean, and TRUE could only have come from CPCB.
UPDATE zone_metrics SET aqi_source = 'MEASURED' WHERE aqi_measured;

-- A window with no AQI has no provenance. This is the correction the blanket
-- default needs, and it is the cheaper direction: nearly every window has air.
UPDATE zone_metrics SET aqi_source = NULL WHERE aqi IS NULL;

ALTER TABLE zone_metrics DROP COLUMN aqi_measured;

-- Added after the backfill, so the scan validates rows that are already right.
ALTER TABLE zone_metrics
    ADD CONSTRAINT ck_zone_metrics_aqi_source
        CHECK (aqi_source IS NULL OR aqi_source IN ('MEASURED', 'MODELLED', 'SYNTHETIC'));

COMMENT ON COLUMN zone_metrics.aqi_source IS
    'Where this window''s AQI came from: MEASURED (instrument), MODELLED '
    '(CAMS), SYNTHETIC (generated). NULL when the window has no AQI.';

-- Reading pattern is "the real windows for this zone", which stays a minority
-- of a large table: MODELLED covers every zone but only from the hour the feed
-- was switched on, against months of generated history.
CREATE INDEX idx_zone_metrics_aqi_source
    ON zone_metrics (zone_id, window_start)
    WHERE aqi_source IN ('MEASURED', 'MODELLED');
