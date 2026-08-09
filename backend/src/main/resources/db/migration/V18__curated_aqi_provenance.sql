-- Record, per curated window, whether its AQI was measured or generated.
--
-- Raw readings already carry this: air_quality_events.demo_data is FALSE for
-- CPCB rows and TRUE for generated ones. The curated layer threw it away —
-- pipeline/aggregate.py wrote demo_data = TRUE on every window it built — and
-- the dashboard reads the curated layer, so a measured AQI and an invented one
-- arrived at the browser indistinguishable from each other.
--
-- Three states, because there are three:
--   NULL   no AQI in this window at all; nothing was measured and nothing
--          was generated, and "not measured" must not read as "synthetic"
--   TRUE   the number came from an instrument
--   FALSE  the number was generated
--
-- Deliberately a separate column rather than a reinterpretation of demo_data.
-- A window is built from traffic and weather as well as air; with traffic still
-- generated, the window as a whole remains demo data even when its AQI is real.
-- A city with real air and synthetic traffic is exactly that, and one flag
-- cannot say both.

ALTER TABLE zone_metrics
    ADD COLUMN aqi_measured BOOLEAN;

COMMENT ON COLUMN zone_metrics.aqi_measured IS
    'TRUE when this window''s AQI came from instruments (CPCB), FALSE when it '
    'was generated, NULL when the window has no AQI.';

-- Existing rows were all built from generated readings, so their AQI is
-- generated wherever it exists. Backfilled rather than left NULL: NULL means
-- "no AQI", and every one of these windows that has an AQI does have a
-- provenance — it is simply not an instrument's.
UPDATE zone_metrics SET aqi_measured = FALSE WHERE aqi IS NOT NULL;

-- Reading pattern is "the measured windows for this zone", which is a small
-- minority of a large table until real feeds outnumber generated ones.
CREATE INDEX idx_zone_metrics_aqi_measured
    ON zone_metrics (zone_id, window_start)
    WHERE aqi_measured;
