-- =============================================================================
-- V4 — Telemetry schema: data sources, raw event tables, curated zone metrics,
--      dead-letter queue and data quality metrics (PRD §19–23)
--
-- Scope. This migration creates only the tables Phase 3 actually writes to.
-- `forecasts`, `anomalies`, `alerts`, `simulations`, `simulation_results`,
-- `recommendations`, `api_keys` and `api_usage` from the PRD §23 list arrive
-- with the phases that populate them (4, 5, 6, 7 and 9). Creating them now
-- would add schema no code writes to, which PRD §39.4 rules out.
--
-- Conventions differ deliberately from V1. The event tables are append-only
-- facts, not entities:
--   * no `deleted_at` — a measurement is never edited or soft-deleted
--   * no `updated_at` — the row is written once
--   * `event_id` carries the producer's identifier so a replayed Kafka offset
--     or a retried Spark micro-batch cannot double-count
--   * `event_time` is when the measurement happened; `ingested_at` is when the
--     platform stored it. Keeping both is what makes late-arriving data
--     detectable rather than silently mis-attributed
--   * BRIN on `event_time`: these tables are written in roughly time order and
--     grow large, which is the case BRIN is built for — a few kilobytes of
--     index where btree would cost hundreds of megabytes
--
-- Partitioning is not used yet. The trigger to revisit is a single event table
-- passing roughly 50M rows or retention becoming a delete problem; declarative
-- monthly range partitioning on `event_time` is the intended next step and the
-- schema is shaped so it can be adopted without changing the columns.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Data sources (PRD §19, §43)
--
-- Every event traces to the source that produced it, so a bad feed can be
-- identified and disabled without guessing which rows it wrote.
-- -----------------------------------------------------------------------------
CREATE TABLE data_sources (
    id               BIGSERIAL PRIMARY KEY,
    uid              UUID         NOT NULL UNIQUE,
    code             VARCHAR(64)  NOT NULL,
    name             VARCHAR(120) NOT NULL,
    description      VARCHAR(255),
    source_type      VARCHAR(24)  NOT NULL,
    -- How the data is obtained. SYNTHETIC is first-class, not a fallback:
    -- PRD §43 requires the platform to run without any external API.
    ingestion_mode   VARCHAR(24)  NOT NULL,
    status           VARCHAR(24)  NOT NULL DEFAULT 'ACTIVE',
    -- Non-secret connection settings only. Credentials stay in the environment
    -- (PRD §30); nothing in this column may be sensitive.
    config           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    last_ingested_at TIMESTAMPTZ,
    demo_data        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT ck_data_sources_type CHECK (source_type IN (
        'TRAFFIC', 'WEATHER', 'AIR_QUALITY', 'INCIDENT', 'CITY_EVENT')),
    CONSTRAINT ck_data_sources_mode CHECK (ingestion_mode IN (
        'SYNTHETIC', 'REST_API', 'STREAM', 'FILE')),
    CONSTRAINT ck_data_sources_status CHECK (status IN (
        'ACTIVE', 'PAUSED', 'FAILED', 'DISABLED'))
);

CREATE UNIQUE INDEX uq_data_sources_code_active ON data_sources (code) WHERE deleted_at IS NULL;
CREATE INDEX idx_data_sources_type_status ON data_sources (source_type, status) WHERE deleted_at IS NULL;

-- -----------------------------------------------------------------------------
-- Traffic events (PRD §9, §20)
-- -----------------------------------------------------------------------------
CREATE TABLE traffic_events (
    id                BIGSERIAL PRIMARY KEY,
    event_id          UUID          NOT NULL,
    zone_id           BIGINT        NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    source_id         BIGINT        NOT NULL REFERENCES data_sources (id) ON DELETE RESTRICT,
    event_time        TIMESTAMPTZ   NOT NULL,
    ingested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    vehicle_count     INTEGER       NOT NULL,
    average_speed_kph NUMERIC(5, 2) NOT NULL,
    -- Vehicles present as a fraction of the zone's road capacity. Allowed above
    -- 1.0: real gridlock exceeds nominal capacity, and clamping it would erase
    -- the exact condition the platform exists to detect.
    occupancy_ratio   NUMERIC(6, 4) NOT NULL,
    congestion_level  VARCHAR(16)   NOT NULL,
    demo_data         BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_traffic_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_traffic_events_vehicles CHECK (vehicle_count >= 0),
    -- 400 km/h is not a plausible urban reading; it is a sensor fault.
    CONSTRAINT ck_traffic_events_speed CHECK (average_speed_kph >= 0 AND average_speed_kph <= 400),
    CONSTRAINT ck_traffic_events_occupancy CHECK (occupancy_ratio >= 0 AND occupancy_ratio <= 10),
    CONSTRAINT ck_traffic_events_congestion CHECK (congestion_level IN (
        'NORMAL', 'MODERATE', 'HIGH', 'CRITICAL'))
);

CREATE INDEX idx_traffic_events_zone_time ON traffic_events (zone_id, event_time DESC);
CREATE INDEX brin_traffic_events_time ON traffic_events USING BRIN (event_time);

-- -----------------------------------------------------------------------------
-- Weather events (PRD §9)
-- -----------------------------------------------------------------------------
CREATE TABLE weather_events (
    id                  BIGSERIAL PRIMARY KEY,
    event_id            UUID          NOT NULL,
    -- Weather is measured per city, not per zone: a single metro's zones share
    -- conditions closely enough that per-zone readings would be false precision.
    city_id             BIGINT        NOT NULL REFERENCES cities (id) ON DELETE RESTRICT,
    source_id           BIGINT        NOT NULL REFERENCES data_sources (id) ON DELETE RESTRICT,
    event_time          TIMESTAMPTZ   NOT NULL,
    ingested_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    temperature_c       NUMERIC(5, 2) NOT NULL,
    humidity_pct        NUMERIC(5, 2) NOT NULL,
    precipitation_mm_h  NUMERIC(6, 2) NOT NULL,
    wind_speed_kph      NUMERIC(5, 2) NOT NULL,
    visibility_km       NUMERIC(5, 2),
    condition           VARCHAR(24)   NOT NULL,
    demo_data           BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_weather_events_event_id UNIQUE (event_id),
    -- Bounds are physical limits, wide enough to admit any real reading.
    CONSTRAINT ck_weather_events_temp CHECK (temperature_c BETWEEN -90 AND 60),
    CONSTRAINT ck_weather_events_humidity CHECK (humidity_pct BETWEEN 0 AND 100),
    CONSTRAINT ck_weather_events_precip CHECK (precipitation_mm_h >= 0 AND precipitation_mm_h <= 500),
    CONSTRAINT ck_weather_events_wind CHECK (wind_speed_kph >= 0 AND wind_speed_kph <= 500),
    CONSTRAINT ck_weather_events_visibility CHECK (visibility_km IS NULL OR visibility_km >= 0),
    CONSTRAINT ck_weather_events_condition CHECK (condition IN (
        'CLEAR', 'CLOUDY', 'OVERCAST', 'LIGHT_RAIN', 'RAIN', 'HEAVY_RAIN',
        'THUNDERSTORM', 'FOG', 'HAZE'))
);

CREATE INDEX idx_weather_events_city_time ON weather_events (city_id, event_time DESC);
CREATE INDEX brin_weather_events_time ON weather_events USING BRIN (event_time);

-- -----------------------------------------------------------------------------
-- Air quality events (PRD §9)
-- -----------------------------------------------------------------------------
CREATE TABLE air_quality_events (
    id          BIGSERIAL PRIMARY KEY,
    event_id    UUID          NOT NULL,
    zone_id     BIGINT        NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    source_id   BIGINT        NOT NULL REFERENCES data_sources (id) ON DELETE RESTRICT,
    event_time  TIMESTAMPTZ   NOT NULL,
    ingested_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    aqi         INTEGER       NOT NULL,
    pm25        NUMERIC(7, 2),
    pm10        NUMERIC(7, 2),
    no2         NUMERIC(7, 2),
    o3          NUMERIC(7, 2),
    co          NUMERIC(7, 3),
    -- Derived from `aqi` by the pipeline rather than trusted from the producer,
    -- so the label and the number can never disagree.
    category    VARCHAR(32)   NOT NULL,
    demo_data   BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT uq_air_quality_events_event_id UNIQUE (event_id),
    CONSTRAINT ck_air_quality_events_aqi CHECK (aqi BETWEEN 0 AND 1000),
    CONSTRAINT ck_air_quality_events_pm25 CHECK (pm25 IS NULL OR pm25 >= 0),
    CONSTRAINT ck_air_quality_events_pm10 CHECK (pm10 IS NULL OR pm10 >= 0),
    CONSTRAINT ck_air_quality_events_category CHECK (category IN (
        'GOOD', 'SATISFACTORY', 'MODERATE', 'POOR', 'VERY_POOR', 'SEVERE'))
);

CREATE INDEX idx_air_quality_events_zone_time ON air_quality_events (zone_id, event_time DESC);
CREATE INDEX brin_air_quality_events_time ON air_quality_events USING BRIN (event_time);

-- -----------------------------------------------------------------------------
-- Incidents (PRD §9, §17)
--
-- Unlike the tables above, an incident has a lifecycle: it opens, may be
-- updated, and closes. Hence `updated_at` and a mutable status.
-- -----------------------------------------------------------------------------
CREATE TABLE incidents (
    id             BIGSERIAL PRIMARY KEY,
    uid            UUID          NOT NULL UNIQUE,
    external_id    VARCHAR(128),
    zone_id        BIGINT        NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    source_id      BIGINT        NOT NULL REFERENCES data_sources (id) ON DELETE RESTRICT,
    incident_type  VARCHAR(32)   NOT NULL,
    severity       VARCHAR(16)   NOT NULL,
    status         VARCHAR(24)   NOT NULL DEFAULT 'REPORTED',
    description    VARCHAR(500),
    latitude       NUMERIC(9, 6) NOT NULL,
    longitude      NUMERIC(9, 6) NOT NULL,
    -- Lanes taken out of service. Feeds the capacity reduction the simulator
    -- and the risk score both need.
    lanes_blocked  SMALLINT,
    started_at     TIMESTAMPTZ   NOT NULL,
    resolved_at    TIMESTAMPTZ,
    ingested_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    demo_data      BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_incidents_type CHECK (incident_type IN (
        'ACCIDENT', 'BREAKDOWN', 'ROAD_CLOSURE', 'CONSTRUCTION', 'FLOODING',
        'PROTEST', 'FIRE', 'SIGNAL_FAILURE', 'OTHER')),
    CONSTRAINT ck_incidents_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_incidents_status CHECK (status IN (
        'REPORTED', 'CONFIRMED', 'IN_PROGRESS', 'CLEARED', 'CANCELLED')),
    CONSTRAINT ck_incidents_latitude CHECK (latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_incidents_longitude CHECK (longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_incidents_lanes CHECK (lanes_blocked IS NULL OR lanes_blocked >= 0),
    -- An incident cannot be resolved before it started.
    CONSTRAINT ck_incidents_resolution_order CHECK (resolved_at IS NULL OR resolved_at >= started_at)
);

-- A feed's own identifier is unique only within that feed.
CREATE UNIQUE INDEX uq_incidents_source_external ON incidents (source_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX idx_incidents_zone_started ON incidents (zone_id, started_at DESC);
-- Serves the dashboard's "what is open right now" query without scanning history.
CREATE INDEX idx_incidents_active ON incidents (zone_id, status) WHERE resolved_at IS NULL;

-- -----------------------------------------------------------------------------
-- City events (PRD §12 — the "stadium event" signal in the correlation example)
-- -----------------------------------------------------------------------------
CREATE TABLE city_events (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID          NOT NULL UNIQUE,
    external_id         VARCHAR(128),
    zone_id             BIGINT        NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    source_id           BIGINT        NOT NULL REFERENCES data_sources (id) ON DELETE RESTRICT,
    event_type          VARCHAR(32)   NOT NULL,
    name                VARCHAR(200)  NOT NULL,
    venue               VARCHAR(200),
    expected_attendance INTEGER,
    starts_at           TIMESTAMPTZ   NOT NULL,
    ends_at             TIMESTAMPTZ   NOT NULL,
    status              VARCHAR(24)   NOT NULL DEFAULT 'SCHEDULED',
    ingested_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    demo_data           BOOLEAN       NOT NULL DEFAULT TRUE,
    CONSTRAINT ck_city_events_type CHECK (event_type IN (
        'SPORTS', 'CONCERT', 'FESTIVAL', 'CONFERENCE', 'PARADE',
        'POLITICAL', 'RELIGIOUS', 'MARKET', 'OTHER')),
    CONSTRAINT ck_city_events_status CHECK (status IN (
        'SCHEDULED', 'ONGOING', 'COMPLETED', 'CANCELLED')),
    CONSTRAINT ck_city_events_attendance CHECK (expected_attendance IS NULL OR expected_attendance >= 0),
    CONSTRAINT ck_city_events_time_order CHECK (ends_at >= starts_at)
);

CREATE UNIQUE INDEX uq_city_events_source_external ON city_events (source_id, external_id)
    WHERE external_id IS NOT NULL;
CREATE INDEX idx_city_events_zone_starts ON city_events (zone_id, starts_at DESC);
-- Supports "which events overlap this window", the correlation engine's entry point.
CREATE INDEX idx_city_events_window ON city_events (starts_at, ends_at);

-- -----------------------------------------------------------------------------
-- Curated zone metrics (PRD §21, §22)
--
-- The windowed aggregate Spark produces, and the only table the dashboard
-- reads for live conditions. Raw events stay queryable for analytics, but no
-- UI path aggregates them on demand — that is what makes the dashboard fast
-- (PRD §44) and keeps a slow query away from the request path.
--
-- One row per zone per window. The unique constraint is what lets Spark write
-- with an upsert, so replaying a batch corrects a window instead of duplicating it.
-- -----------------------------------------------------------------------------
CREATE TABLE zone_metrics (
    id                   BIGSERIAL PRIMARY KEY,
    zone_id              BIGINT        NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    window_start         TIMESTAMPTZ   NOT NULL,
    window_end           TIMESTAMPTZ   NOT NULL,
    -- Traffic
    vehicle_count        INTEGER,
    average_speed_kph    NUMERIC(5, 2),
    occupancy_ratio      NUMERIC(6, 4),
    congestion_level     VARCHAR(16),
    -- Air quality
    aqi                  INTEGER,
    aqi_category         VARCHAR(32),
    -- Weather, denormalised from the city reading so one row answers the
    -- dashboard's per-zone question without a join per tile.
    temperature_c        NUMERIC(5, 2),
    precipitation_mm_h   NUMERIC(6, 2),
    weather_condition    VARCHAR(24),
    -- Context
    active_incidents     SMALLINT      NOT NULL DEFAULT 0,
    active_events        SMALLINT      NOT NULL DEFAULT 0,
    -- Composite 0–100 risk. Derived, never a producer input.
    risk_score           NUMERIC(5, 2),
    risk_level           VARCHAR(16),
    -- How many raw events the window was computed from. A window built from two
    -- events is not as trustworthy as one built from sixty, and the UI needs to
    -- be able to say so rather than presenting both identically.
    sample_count         INTEGER       NOT NULL DEFAULT 0,
    demo_data            BOOLEAN       NOT NULL DEFAULT TRUE,
    computed_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_zone_metrics_zone_window UNIQUE (zone_id, window_start, window_end),
    CONSTRAINT ck_zone_metrics_window_order CHECK (window_end > window_start),
    CONSTRAINT ck_zone_metrics_congestion CHECK (congestion_level IS NULL OR congestion_level IN (
        'NORMAL', 'MODERATE', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_zone_metrics_risk_level CHECK (risk_level IS NULL OR risk_level IN (
        'NORMAL', 'MODERATE', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_zone_metrics_risk_score CHECK (risk_score IS NULL OR risk_score BETWEEN 0 AND 100),
    CONSTRAINT ck_zone_metrics_samples CHECK (sample_count >= 0)
);

-- The dashboard's hot path: newest window for a zone.
CREATE INDEX idx_zone_metrics_zone_window ON zone_metrics (zone_id, window_start DESC);
CREATE INDEX brin_zone_metrics_window ON zone_metrics USING BRIN (window_start);

-- -----------------------------------------------------------------------------
-- Dead-letter queue
--
-- Records that failed validation. They are kept, not dropped: a malformed feed
-- is itself a signal, and the exit criterion for this phase is that an invalid
-- record is explainable — which needs the payload that caused it.
--
-- `raw_payload` is TEXT, not JSONB, precisely because the reason for rejection
-- may be that it was not valid JSON at all.
-- -----------------------------------------------------------------------------
CREATE TABLE ingestion_dlq (
    id            BIGSERIAL PRIMARY KEY,
    uid           UUID         NOT NULL UNIQUE,
    source_id     BIGINT       REFERENCES data_sources (id) ON DELETE SET NULL,
    topic         VARCHAR(120) NOT NULL,
    kafka_offset  BIGINT,
    kafka_partition INTEGER,
    event_type    VARCHAR(24),
    reason_code   VARCHAR(48)  NOT NULL,
    reason_detail VARCHAR(500),
    raw_payload   TEXT,
    event_time    TIMESTAMPTZ,
    rejected_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_ingestion_dlq_reason CHECK (reason_code IN (
        'MALFORMED_JSON',
        'SCHEMA_MISMATCH',
        'MISSING_REQUIRED_FIELD',
        'UNKNOWN_ZONE',
        'UNKNOWN_CITY',
        'UNKNOWN_SOURCE',
        'VALUE_OUT_OF_RANGE',
        'TIMESTAMP_INVALID',
        'TIMESTAMP_TOO_OLD',
        'TIMESTAMP_IN_FUTURE',
        'DUPLICATE_EVENT_ID',
        'UNSUPPORTED_EVENT_TYPE'))
);

CREATE INDEX idx_ingestion_dlq_reason_time ON ingestion_dlq (reason_code, rejected_at DESC);
CREATE INDEX idx_ingestion_dlq_source ON ingestion_dlq (source_id, rejected_at DESC);
CREATE INDEX brin_ingestion_dlq_rejected ON ingestion_dlq USING BRIN (rejected_at);

-- -----------------------------------------------------------------------------
-- Data quality metrics (PRD §24, §35)
--
-- One row per source per batch window, so "is the pipeline healthy" is a query
-- rather than a log search. This is the table behind the phase exit criterion
-- "data quality metrics are queryable".
-- -----------------------------------------------------------------------------
CREATE TABLE data_quality_metrics (
    id                BIGSERIAL PRIMARY KEY,
    source_id         BIGINT       REFERENCES data_sources (id) ON DELETE SET NULL,
    stage             VARCHAR(32)  NOT NULL,
    window_start      TIMESTAMPTZ  NOT NULL,
    window_end        TIMESTAMPTZ  NOT NULL,
    records_received  BIGINT       NOT NULL DEFAULT 0,
    records_valid     BIGINT       NOT NULL DEFAULT 0,
    records_rejected  BIGINT       NOT NULL DEFAULT 0,
    records_duplicate BIGINT       NOT NULL DEFAULT 0,
    records_late      BIGINT       NOT NULL DEFAULT 0,
    -- Stored rather than computed on read: it is the number most often charted,
    -- and deriving it every time would mean repeating the zero-division guard
    -- in every consumer.
    validity_ratio    NUMERIC(5, 4),
    max_lag_seconds   INTEGER,
    computed_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_dq_metrics_source_stage_window UNIQUE (source_id, stage, window_start, window_end),
    CONSTRAINT ck_dq_metrics_stage CHECK (stage IN ('INGEST', 'VALIDATE', 'AGGREGATE', 'LOAD')),
    CONSTRAINT ck_dq_metrics_window_order CHECK (window_end > window_start),
    CONSTRAINT ck_dq_metrics_counts CHECK (
        records_received >= 0 AND records_valid >= 0
        AND records_rejected >= 0 AND records_duplicate >= 0 AND records_late >= 0),
    CONSTRAINT ck_dq_metrics_ratio CHECK (validity_ratio IS NULL OR validity_ratio BETWEEN 0 AND 1)
);

CREATE INDEX idx_dq_metrics_window ON data_quality_metrics (window_start DESC);
CREATE INDEX idx_dq_metrics_source_stage ON data_quality_metrics (source_id, stage, window_start DESC);
