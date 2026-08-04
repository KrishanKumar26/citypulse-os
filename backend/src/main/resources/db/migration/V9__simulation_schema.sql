-- =============================================================================
-- V9 — What-If Simulator (PRD §14)
--
-- A simulation is a *counterfactual*: it starts from conditions the city really
-- was in, applies a hypothetical change, and reports what the engine's model
-- says would follow. Three things follow from that and shape this schema:
--
-- 1. The baseline must be pinned. `baseline_window` records the exact curated
--    window the scenario departed from, so a result stays interpretable after
--    conditions move on. Without it, "traffic +43%" is a percentage of nothing
--    in particular.
--
-- 2. The engine version must be recorded. Assumptions change; a result computed
--    under different assumptions is a different result, and re-reading an old
--    simulation months later should say which model produced it.
--
-- 3. Simulated output must never be mistakable for measurement. Results live in
--    their own tables rather than alongside `zone_metrics`, and every row is
--    flagged. A simulated risk score that reached the alerting engine would
--    raise an alert about something that never happened.
-- =============================================================================

CREATE TABLE simulations (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID          NOT NULL UNIQUE,

    city_id             BIGINT        NOT NULL REFERENCES cities (id) ON DELETE RESTRICT,
    created_by          BIGINT        REFERENCES users (id) ON DELETE SET NULL,

    name                VARCHAR(200)  NOT NULL,
    description         VARCHAR(1000),

    -- The scenario as submitted. Stored verbatim so a run can be reproduced and
    -- audited, and so a UI can repopulate the form that produced it.
    scenario            JSONB         NOT NULL,

    -- Which curated window the counterfactual departed from.
    baseline_window     TIMESTAMPTZ   NOT NULL,

    -- The engine that computed this. Assumptions are versioned because they are
    -- the thing a reader most needs to be able to check.
    engine_version      VARCHAR(32)   NOT NULL,

    status              VARCHAR(24)   NOT NULL DEFAULT 'COMPLETED',
    error_detail        VARCHAR(1000),

    -- City-wide deltas, for the summary line. Per-zone detail is in
    -- simulation_results.
    traffic_change_pct  NUMERIC(8, 2),
    crowd_change_pct    NUMERIC(8, 2),
    parking_change_pct  NUMERIC(8, 2),
    delay_change_min    NUMERIC(8, 2),
    baseline_risk       NUMERIC(6, 2),
    simulated_risk      NUMERIC(6, 2),
    zones_affected      INTEGER       NOT NULL DEFAULT 0,

    -- Actions the engine derived from the outcome, each tied to a zone and a
    -- reason. Stored rather than recomputed so advice does not silently change.
    recommendations     JSONB         NOT NULL DEFAULT '[]'::jsonb,

    computed_ms         INTEGER,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_simulations_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    -- A failed run must say why; a completed one must not carry an error.
    CONSTRAINT ck_simulations_error CHECK (
        (status = 'FAILED' AND error_detail IS NOT NULL)
        OR (status <> 'FAILED' AND error_detail IS NULL))
);

CREATE INDEX idx_simulations_city_created ON simulations (city_id, created_at DESC);
CREATE INDEX idx_simulations_creator ON simulations (created_by, created_at DESC);

-- -----------------------------------------------------------------------------
-- Per-zone outcome
--
-- Both sides are stored, not just the delta. A reader comparing "before" and
-- "after" needs the actual numbers: a +43% change from 0.3 and from 0.9 are
-- entirely different situations, and only one of them is a problem.
-- -----------------------------------------------------------------------------
CREATE TABLE simulation_results (
    id                      BIGSERIAL PRIMARY KEY,
    simulation_id           BIGINT       NOT NULL REFERENCES simulations (id) ON DELETE CASCADE,
    zone_id                 BIGINT       NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,

    -- Observed baseline, copied from the curated window at simulation time.
    baseline_occupancy      NUMERIC(6, 4),
    baseline_speed_kph      NUMERIC(6, 2),
    baseline_vehicle_count  INTEGER,
    baseline_risk_score     NUMERIC(6, 2),
    baseline_congestion     VARCHAR(16),

    -- Counterfactual, computed by the engine.
    simulated_occupancy     NUMERIC(6, 4),
    simulated_speed_kph     NUMERIC(6, 2),
    simulated_vehicle_count INTEGER,
    simulated_risk_score    NUMERIC(6, 2),
    simulated_congestion    VARCHAR(16),

    -- Derived impacts the PRD asks to display (§14).
    delay_change_min        NUMERIC(8, 2),
    parking_change_pct      NUMERIC(8, 2),
    crowd_change_pct        NUMERIC(8, 2),

    -- Whether this zone was directly targeted by the scenario or affected
    -- through spillover. The distinction matters: a directly closed road is a
    -- stated input, while a neighbouring zone's congestion is the engine's
    -- inference and deserves less confidence.
    impact_source           VARCHAR(16)  NOT NULL DEFAULT 'DIRECT',

    CONSTRAINT uq_simulation_results UNIQUE (simulation_id, zone_id),
    CONSTRAINT ck_simulation_results_source CHECK (impact_source IN ('DIRECT', 'SPILLOVER', 'CITYWIDE')),
    CONSTRAINT ck_simulation_results_congestion CHECK (
        simulated_congestion IS NULL OR simulated_congestion IN (
            'NORMAL', 'MODERATE', 'HIGH', 'CRITICAL'))
);

CREATE INDEX idx_simulation_results_simulation ON simulation_results (simulation_id);
CREATE INDEX idx_simulation_results_zone ON simulation_results (zone_id);
