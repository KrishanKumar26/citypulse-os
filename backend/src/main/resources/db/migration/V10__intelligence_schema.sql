-- =============================================================================
-- V10 — AI Intelligence & City Memory (PRD §13, §15, §16, §17)
--
-- Four capabilities, one principle: the platform may only say things it can
-- point at data for.
--
--   zone_baselines    what "normal" is, learned per zone and per hour-of-week
--   anomalies         deviations from that normal, with the numbers behind them
--   situation_memory  past situations and what actually followed
--   correlations      measured co-occurrence between conditions
--
-- The alerting in V6 fires on fixed thresholds — occupancy above 1.0. That is a
-- different and simpler thing than what is here. An anomaly is a departure from
-- what this zone normally does at this hour, so 8,000 vehicles can be perfectly
-- normal on a Tuesday morning and a genuine anomaly at 3 a.m. A threshold
-- cannot express that; a learned baseline can.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Learned baselines
--
-- One row per (zone, metric, hour-of-week). 168 buckets covers daily and weekly
-- seasonality together, which matters because Tuesday 09:00 and Sunday 09:00 are
-- not the same city.
--
-- Robust statistics — median and median absolute deviation — rather than mean
-- and standard deviation. A baseline learned from history that contains
-- anomalies would absorb them with a mean: the spikes it is meant to detect
-- would raise the "normal" it compares against. The median barely moves.
-- -----------------------------------------------------------------------------
CREATE TABLE zone_baselines (
    id                  BIGSERIAL PRIMARY KEY,
    zone_id             BIGINT       NOT NULL REFERENCES zones (id) ON DELETE CASCADE,
    metric              VARCHAR(32)  NOT NULL,

    -- 0 = Monday 00:00, 167 = Sunday 23:00, in the city's own timezone.
    hour_of_week        SMALLINT     NOT NULL,

    median_value        NUMERIC(12, 4) NOT NULL,
    -- Median absolute deviation. Scaled by 1.4826 at read time to be comparable
    -- with a standard deviation on normally distributed data.
    mad                 NUMERIC(12, 4) NOT NULL,
    p10                 NUMERIC(12, 4),
    p90                 NUMERIC(12, 4),

    -- How many windows produced this bucket. Below a floor the bucket is not
    -- trustworthy and detection must decline rather than guess (PRD §15).
    sample_count        INTEGER      NOT NULL,

    learned_from        TIMESTAMPTZ  NOT NULL,
    learned_to          TIMESTAMPTZ  NOT NULL,
    computed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_zone_baselines UNIQUE (zone_id, metric, hour_of_week),
    CONSTRAINT ck_zone_baselines_hour CHECK (hour_of_week BETWEEN 0 AND 167),
    CONSTRAINT ck_zone_baselines_metric CHECK (metric IN (
        'occupancy_ratio', 'average_speed_kph', 'vehicle_count', 'aqi', 'risk_score')),
    CONSTRAINT ck_zone_baselines_mad CHECK (mad >= 0),
    CONSTRAINT ck_zone_baselines_samples CHECK (sample_count > 0),
    CONSTRAINT ck_zone_baselines_window CHECK (learned_to > learned_from)
);

CREATE INDEX idx_zone_baselines_lookup ON zone_baselines (zone_id, metric, hour_of_week);

-- -----------------------------------------------------------------------------
-- Anomalies
--
-- Every row carries the observed value, the baseline it was compared against,
-- and the deviation between them. That triple is the explanation: an anomaly
-- that cannot be stated as "17,800 against a normal of 8,000" is one the user
-- has to take on faith (PRD §13, §15).
-- -----------------------------------------------------------------------------
CREATE TABLE anomalies (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID         NOT NULL UNIQUE,

    zone_id             BIGINT       NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    city_id             BIGINT       NOT NULL REFERENCES cities (id) ON DELETE RESTRICT,

    metric              VARCHAR(32)  NOT NULL,
    anomaly_type        VARCHAR(24)  NOT NULL,
    severity            VARCHAR(16)  NOT NULL,

    -- The window that was judged, and the evidence.
    window_start        TIMESTAMPTZ  NOT NULL,
    observed_value      NUMERIC(12, 4) NOT NULL,
    baseline_value      NUMERIC(12, 4) NOT NULL,
    baseline_mad        NUMERIC(12, 4) NOT NULL,
    -- Robust z-score: |observed - median| / (1.4826 * MAD).
    deviation_score     NUMERIC(10, 4) NOT NULL,
    -- Change against the baseline, for the human-readable form.
    percent_change      NUMERIC(10, 2),

    baseline_samples    INTEGER      NOT NULL,

    -- Written at detection time so it survives the code that produced it.
    explanation         VARCHAR(1000) NOT NULL,

    detected_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    demo_data           BOOLEAN      NOT NULL DEFAULT TRUE,

    -- Present only on rows created by the evaluation harness, which injects
    -- known anomalies to measure precision. NULL for anything detected in
    -- ordinary operation, so a measured figure can never be contaminated by
    -- production data (PRD Phase 7 exit criterion).
    injected_label      VARCHAR(16),

    CONSTRAINT uq_anomalies_window UNIQUE (zone_id, metric, window_start),
    CONSTRAINT ck_anomalies_type CHECK (anomaly_type IN ('SPIKE', 'DROP', 'SUSTAINED_SHIFT')),
    CONSTRAINT ck_anomalies_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_anomalies_metric CHECK (metric IN (
        'occupancy_ratio', 'average_speed_kph', 'vehicle_count', 'aqi', 'risk_score')),
    CONSTRAINT ck_anomalies_label CHECK (injected_label IS NULL OR injected_label IN (
        'TRUE_ANOMALY', 'NORMAL')),
    CONSTRAINT ck_anomalies_deviation CHECK (deviation_score >= 0),
    CONSTRAINT ck_anomalies_samples CHECK (baseline_samples > 0)
);

CREATE INDEX idx_anomalies_city_detected ON anomalies (city_id, detected_at DESC);
CREATE INDEX idx_anomalies_zone_window ON anomalies (zone_id, window_start DESC);
CREATE INDEX idx_anomalies_severity ON anomalies (severity, detected_at DESC);
CREATE INDEX brin_anomalies_window ON anomalies USING BRIN (window_start);

-- -----------------------------------------------------------------------------
-- City Memory (PRD §16)
--
-- A situation is a coarse fingerprint of conditions — rain band, day type, hour
-- band, event presence, incident band — paired with what actually followed it.
--
-- Coarse on purpose. Matching on exact values would make every situation unique
-- and the memory useless; the question "has this happened before" is about kind,
-- not precision. The outcome fields hold real measured deltas over the following
-- hours, so a recalled situation reports what happened rather than what a model
-- thinks would happen.
-- -----------------------------------------------------------------------------
CREATE TABLE situation_memory (
    id                      BIGSERIAL PRIMARY KEY,
    uid                     UUID         NOT NULL UNIQUE,

    zone_id                 BIGINT       NOT NULL REFERENCES zones (id) ON DELETE CASCADE,
    city_id                 BIGINT       NOT NULL REFERENCES cities (id) ON DELETE CASCADE,

    occurred_at             TIMESTAMPTZ  NOT NULL,

    -- The fingerprint. Stored as separate columns rather than a hash so a
    -- similar-but-not-identical situation can still be matched, and so a stored
    -- row is readable without the code that wrote it.
    rain_band               VARCHAR(12)  NOT NULL,
    day_type                VARCHAR(12)  NOT NULL,
    hour_band               VARCHAR(16)  NOT NULL,
    had_event               BOOLEAN      NOT NULL,
    incident_band           VARCHAR(12)  NOT NULL,
    congestion_band         VARCHAR(16)  NOT NULL,

    -- Conditions at the moment the situation was recorded.
    occupancy_at_start      NUMERIC(6, 4),
    speed_at_start          NUMERIC(6, 2),
    risk_at_start           NUMERIC(6, 2),

    -- What actually followed, measured over the outcome horizon. These are
    -- observations, not predictions — the whole value of the memory.
    outcome_horizon_minutes INTEGER      NOT NULL,
    peak_occupancy          NUMERIC(6, 4),
    min_speed_kph           NUMERIC(6, 2),
    peak_risk               NUMERIC(6, 2),
    occupancy_change_pct    NUMERIC(10, 2),
    speed_change_pct        NUMERIC(10, 2),
    risk_change_pct         NUMERIC(10, 2),

    demo_data               BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_situation_memory UNIQUE (zone_id, occurred_at),
    CONSTRAINT ck_situation_rain CHECK (rain_band IN ('NONE', 'LIGHT', 'MODERATE', 'HEAVY')),
    CONSTRAINT ck_situation_day CHECK (day_type IN ('WEEKDAY', 'WEEKEND')),
    CONSTRAINT ck_situation_hour CHECK (hour_band IN (
        'OVERNIGHT', 'MORNING_PEAK', 'MIDDAY', 'EVENING_PEAK', 'EVENING')),
    CONSTRAINT ck_situation_incident CHECK (incident_band IN ('NONE', 'SOME', 'MANY')),
    CONSTRAINT ck_situation_congestion CHECK (congestion_band IN (
        'NORMAL', 'MODERATE', 'HIGH', 'CRITICAL'))
);

-- The recall query matches on the fingerprint, so it is indexed as a whole.
CREATE INDEX idx_situation_fingerprint ON situation_memory
    (city_id, rain_band, day_type, hour_band, had_event, incident_band);
CREATE INDEX idx_situation_zone_time ON situation_memory (zone_id, occurred_at DESC);

-- -----------------------------------------------------------------------------
-- Measured correlations (PRD §12)
--
-- Not a claim that one thing causes another — a measurement of how often they
-- occur together, with the counts that produced it. Lift above 1 means the
-- pairing is more common than chance; the sample counts are stored so a reader
-- can see whether that is worth anything.
-- -----------------------------------------------------------------------------
CREATE TABLE condition_correlations (
    id                  BIGSERIAL PRIMARY KEY,
    city_id             BIGINT       NOT NULL REFERENCES cities (id) ON DELETE CASCADE,

    condition_a         VARCHAR(48)  NOT NULL,
    condition_b         VARCHAR(48)  NOT NULL,

    -- P(B | A) / P(B). Above 1.0 means A raises the odds of B.
    lift                NUMERIC(10, 4) NOT NULL,
    support             NUMERIC(8, 6)  NOT NULL,
    confidence          NUMERIC(8, 6)  NOT NULL,

    windows_with_a      INTEGER      NOT NULL,
    windows_with_both   INTEGER      NOT NULL,
    windows_total       INTEGER      NOT NULL,

    computed_from       TIMESTAMPTZ  NOT NULL,
    computed_to         TIMESTAMPTZ  NOT NULL,
    computed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_condition_correlations UNIQUE (city_id, condition_a, condition_b),
    CONSTRAINT ck_correlations_counts CHECK (
        windows_total > 0 AND windows_with_a > 0 AND windows_with_both >= 0
        AND windows_with_both <= windows_with_a AND windows_with_a <= windows_total),
    CONSTRAINT ck_correlations_lift CHECK (lift >= 0)
);

CREATE INDEX idx_correlations_city_lift ON condition_correlations (city_id, lift DESC);
