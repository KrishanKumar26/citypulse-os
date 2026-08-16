-- =============================================================================
-- V22 — the third signal that stops being invented, and the one that matters.
--
-- V19 gave air three provenances, V21 gave weather one. Traffic is different in
-- kind: `occupancy_ratio` is what risk, anomalies, forecasts and alerts are all
-- computed from, so it is the feed whose provenance decides what the product is
-- actually worth. TomTom's Traffic API covers India for both flow and incidents,
-- and a probe of all sixty-two zone centres answered every one of them — median
-- snap distance 60 m, confidence 0.74 to 1.00.
--
-- WHAT IS STORED, AND WHY IT IS NOT OCCUPANCY
--
-- TomTom does not count vehicles. It reports the speed on a segment now against
-- the speed that segment runs at unloaded. That is a *delay*, and this platform's
-- `occupancy_ratio` is a *fullness* — vehicles present over road capacity. They
-- are not the same quantity and they do not share a scale.
--
-- The platform already owns a relationship between them, `speed_from_occupancy`
-- in common/transforms.py, and inverting it is exact: feed 0.40 in, get 47.59
-- km/h out, invert, get 0.400 back. It was measured against 124 real readings
-- before being rejected, and it was rejected on the numbers:
--
--     40/40 kph   ratio 1.000  ->  occupancy 0.000     "the road is empty"
--     39/40 kph   ratio 0.975  ->  occupancy 0.496
--
-- Twenty-nine percent of real readings land on exactly 0.000 — free-flowing
-- roads at ten in the morning, reported as empty — and because TomTom returns
-- whole km/h, one unit of rounding swings the metric across half its range. A
-- derived number that behaves like that is worse than no number.
--
-- So the measurement is stored as the thing that was measured. Zones carrying
-- real traffic report `speed_ratio` and `average_speed_kph`, and their
-- `occupancy_ratio` and `vehicle_count` are NULL — not zero. Nothing counted the
-- vehicles, and the platform's oldest rule is that a gap is stated rather than
-- filled. Baselines, anomalies and the situation card all read relative to a
-- learned baseline, so they carry across unchanged; only `risk_score` needed a
-- branch, and it has one.
--
-- TWO SOURCES, ONE PROVIDER
--
-- TomTom returns `confidence`, 0 to 1, for how much of a reading came from live
-- probes rather than from its own historical model. That is exactly the
-- MEASURED/MODELLED distinction this schema already makes, so it is made the way
-- the schema makes it: two source rows, and the ingester files each reading
-- under the one its confidence earns. A single row marked MEASURED would put an
-- instrument's label on a model's output whenever the probes thinned out.
-- =============================================================================

INSERT INTO data_sources (uid, code, name, description, source_type,
                          ingestion_mode, status, config, demo_data, provenance,
                          attribution) VALUES
    (gen_random_uuid(),
     'tomtom-traffic',
     'TomTom Traffic Flow (live)',
     -- description is VARCHAR(255). The reasoning lives in ingest/tomtom.py.
     'Segment speeds from vehicle probes, matched to the road nearest each zone '
     || 'centre. Readings TomTom reports high confidence in, meaning the speed '
     || 'came from vehicles on the road rather than from its historical model.',
     'TRAFFIC',
     'REST_API',
     -- PAUSED until it delivers, like every other real feed here.
     'PAUSED',
     '{"provider": "tomtom.com", "service": "flowSegmentData",
       "requiresKey": true, "minConfidence": 0.9, "maxSnapKm": 2.0}'::jsonb,
     FALSE,
     'MEASURED',
     '[{"name": "TomTom Traffic API", "url": "https://developer.tomtom.com/"}]'::jsonb),

    (gen_random_uuid(),
     'tomtom-traffic-modelled',
     'TomTom Traffic Flow (modelled)',
     'The same segments where TomTom reports lower confidence: the speed leans '
     || 'on its historical model for that road and hour rather than on vehicles '
     || 'observed now. Real road, real hour, no instrument reporting it.',
     'TRAFFIC',
     'REST_API',
     'PAUSED',
     '{"provider": "tomtom.com", "service": "flowSegmentData",
       "requiresKey": true, "minConfidence": 0.5, "maxSnapKm": 2.0}'::jsonb,
     FALSE,
     'MODELLED',
     '[{"name": "TomTom Traffic API", "url": "https://developer.tomtom.com/"}]'::jsonb);

-- ---------------------------------------------------------------------------
-- Raw traffic events gain the speed form
--
-- A generated event states vehicles, fullness and a congestion band. A probed
-- event states speed and the free-flow speed it is measured against. Both are
-- complete descriptions of a road; neither can produce the other's fields
-- without inventing something.
--
-- Rather than drop NOT NULL and let any column be absent, the three that the
-- generator guarantees become conditionally required: a row must state one form
-- or the other in full. A writer that half-fills either shape is rejected by the
-- database instead of arriving as a partial row nobody notices.
-- ---------------------------------------------------------------------------

ALTER TABLE traffic_events ADD COLUMN speed_ratio         NUMERIC(5, 4);
ALTER TABLE traffic_events ADD COLUMN free_flow_speed_kph NUMERIC(5, 2);
ALTER TABLE traffic_events ADD COLUMN confidence          NUMERIC(4, 3);

ALTER TABLE traffic_events ALTER COLUMN vehicle_count    DROP NOT NULL;
ALTER TABLE traffic_events ALTER COLUMN occupancy_ratio  DROP NOT NULL;
ALTER TABLE traffic_events ALTER COLUMN congestion_level DROP NOT NULL;

ALTER TABLE traffic_events
    ADD CONSTRAINT ck_traffic_events_one_complete_form
        CHECK (
            (vehicle_count IS NOT NULL
             AND occupancy_ratio IS NOT NULL
             AND congestion_level IS NOT NULL)
            OR
            (speed_ratio IS NOT NULL
             AND free_flow_speed_kph IS NOT NULL)
        );

-- Above 1.0 is a segment running faster than its own free-flow reference, which
-- TomTom does report on a quiet motorway. Kept rather than clamped, for the same
-- reason occupancy is allowed above 1.0.
ALTER TABLE traffic_events
    ADD CONSTRAINT ck_traffic_events_speed_ratio
        CHECK (speed_ratio IS NULL OR speed_ratio BETWEEN 0 AND 2);

ALTER TABLE traffic_events
    ADD CONSTRAINT ck_traffic_events_confidence
        CHECK (confidence IS NULL OR confidence BETWEEN 0 AND 1);

COMMENT ON COLUMN traffic_events.speed_ratio IS
    'Current speed over free-flow speed on this segment. 1.0 is unimpeded, 0.5 '
    'is half speed. Not occupancy: nothing here counted vehicles.';
COMMENT ON COLUMN traffic_events.confidence IS
    'TomTom''s 0-1 measure of how much of the reading came from live vehicle '
    'probes rather than its historical model. Decides MEASURED against '
    'MODELLED. NULL for generated rows, which have no such notion.';

-- ---------------------------------------------------------------------------
-- Curated windows record their traffic and where it came from
-- ---------------------------------------------------------------------------

ALTER TABLE zone_metrics ADD COLUMN speed_ratio NUMERIC(5, 4);

ALTER TABLE zone_metrics
    ADD CONSTRAINT ck_zone_metrics_speed_ratio
        CHECK (speed_ratio IS NULL OR speed_ratio BETWEEN 0 AND 2);

-- Same shape and same reasoning as weather_source in V21: a constant default is
-- catalogue-only, so this does not rewrite a table holding months of windows,
-- and the default is dropped afterwards so a writer that states no provenance
-- fails loudly rather than being handed this schema's worst answer.
ALTER TABLE zone_metrics ADD COLUMN traffic_source VARCHAR(16) DEFAULT 'SYNTHETIC';
ALTER TABLE zone_metrics ALTER COLUMN traffic_source DROP DEFAULT;

-- A window with no traffic reading has no provenance. At this migration every
-- existing row is generated, so occupancy alone identifies them; speed_ratio is
-- NULL everywhere until the ingester first runs.
UPDATE zone_metrics SET traffic_source = NULL WHERE occupancy_ratio IS NULL;

ALTER TABLE zone_metrics
    ADD CONSTRAINT ck_zone_metrics_traffic_source
        CHECK (traffic_source IS NULL
               OR traffic_source IN ('MEASURED', 'MODELLED', 'SYNTHETIC'));

COMMENT ON COLUMN zone_metrics.speed_ratio IS
    'Current speed over free-flow speed, from a real feed. NULL on generated '
    'windows, which describe the road as occupancy_ratio instead. The two are '
    'never both present: they are different measurements of the same road and a '
    'row carrying both would invite them to disagree.';
COMMENT ON COLUMN zone_metrics.traffic_source IS
    'Where this window''s traffic came from: MEASURED (vehicle probes), '
    'MODELLED (a traffic model of a real road), SYNTHETIC (generated). NULL '
    'when the window has no traffic reading at all.';

CREATE INDEX idx_zone_metrics_traffic_source
    ON zone_metrics (zone_id, window_start)
    WHERE traffic_source IN ('MEASURED', 'MODELLED');
