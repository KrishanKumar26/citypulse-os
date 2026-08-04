-- =============================================================================
-- V6 — Alerts (PRD §17)
--
-- Alerts are derived, not ingested: a rule evaluates curated zone metrics and
-- raises one when a condition holds. That makes them the first thing in the
-- platform that says something rather than only reports something, so two
-- properties matter more than usual.
--
-- 1. Every alert cites the window it came from. `zone_metric_window_start`
--    plus `metric_name`/`observed_value` are what let the UI show *why* an
--    alert fired instead of asserting it (PRD §15). An alert that cannot be
--    traced back to a row is exactly the invented fact the PRD forbids.
--
-- 2. An alert must not re-raise every evaluation cycle. `dedupe_key` is unique
--    among open alerts, so a zone that stays congested for an hour produces one
--    alert that stays open, not sixty. Without that the Alert Center becomes
--    noise and gets ignored, which is the failure mode alerting actually dies of.
-- =============================================================================

CREATE TABLE alerts (
    id                       BIGSERIAL PRIMARY KEY,
    uid                      UUID          NOT NULL UNIQUE,

    -- What and where
    alert_type               VARCHAR(24)   NOT NULL,
    severity                 VARCHAR(16)   NOT NULL,
    status                   VARCHAR(24)   NOT NULL DEFAULT 'NEW',
    title                    VARCHAR(200)  NOT NULL,
    description              VARCHAR(1000) NOT NULL,
    -- Nullable: a SYSTEM or DATA_QUALITY alert concerns the platform, not a place.
    zone_id                  BIGINT        REFERENCES zones (id) ON DELETE RESTRICT,
    city_id                  BIGINT        REFERENCES cities (id) ON DELETE RESTRICT,

    -- Provenance. The rule that fired, and the measurement it fired on.
    rule_code                VARCHAR(64)   NOT NULL,
    metric_name              VARCHAR(64),
    observed_value           NUMERIC(12, 4),
    threshold_value          NUMERIC(12, 4),
    -- The exact curated window this was computed from, so the UI can link an
    -- alert to the data behind it.
    zone_metric_window_start TIMESTAMPTZ,

    recommended_action       VARCHAR(500),

    -- Lifecycle
    raised_at                TIMESTAMPTZ   NOT NULL DEFAULT now(),
    acknowledged_at          TIMESTAMPTZ,
    acknowledged_by          BIGINT        REFERENCES users (id) ON DELETE SET NULL,
    resolved_at              TIMESTAMPTZ,
    resolved_by              BIGINT        REFERENCES users (id) ON DELETE SET NULL,
    resolution_note          VARCHAR(500),

    -- Identity for suppression: rule + subject + a coarse time bucket. Two
    -- evaluations of the same condition in the same window produce the same key.
    dedupe_key               VARCHAR(200)  NOT NULL,

    demo_data                BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_alerts_type CHECK (alert_type IN (
        'CRITICAL', 'WARNING', 'INFORMATIONAL', 'SYSTEM', 'DATA_QUALITY', 'SECURITY')),
    CONSTRAINT ck_alerts_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_alerts_status CHECK (status IN (
        'NEW', 'ACKNOWLEDGED', 'INVESTIGATING', 'RESOLVED')),
    -- A resolved alert must record when, and an unresolved one must not.
    CONSTRAINT ck_alerts_resolution CHECK (
        (status = 'RESOLVED' AND resolved_at IS NOT NULL)
        OR (status <> 'RESOLVED' AND resolved_at IS NULL)),
    CONSTRAINT ck_alerts_ack_order CHECK (
        acknowledged_at IS NULL OR acknowledged_at >= raised_at),
    CONSTRAINT ck_alerts_resolve_order CHECK (
        resolved_at IS NULL OR resolved_at >= raised_at)
);

-- One open alert per condition. Partial, so the same condition can legitimately
-- raise again after the previous one is resolved.
CREATE UNIQUE INDEX uq_alerts_dedupe_open ON alerts (dedupe_key)
    WHERE status <> 'RESOLVED';

-- The Alert Center's default view: everything still open, newest first.
CREATE INDEX idx_alerts_open ON alerts (raised_at DESC) WHERE status <> 'RESOLVED';
CREATE INDEX idx_alerts_zone_raised ON alerts (zone_id, raised_at DESC);
CREATE INDEX idx_alerts_city_status ON alerts (city_id, status);
CREATE INDEX idx_alerts_severity_status ON alerts (severity, status);
CREATE INDEX brin_alerts_raised ON alerts USING BRIN (raised_at);

-- -----------------------------------------------------------------------------
-- Read-path index for live metrics
--
-- The dashboard's hot query is "the newest window for every zone in a city".
-- zone_metrics already has (zone_id, window_start DESC), which serves one zone;
-- this covering index lets the per-city sweep read the values it needs straight
-- from the index without visiting the heap for every zone.
-- -----------------------------------------------------------------------------
CREATE INDEX idx_zone_metrics_latest_covering
    ON zone_metrics (zone_id, window_start DESC)
    INCLUDE (occupancy_ratio, average_speed_kph, congestion_level, aqi,
             risk_score, risk_level, active_incidents, vehicle_count);
