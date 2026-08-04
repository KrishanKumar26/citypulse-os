-- =============================================================================
-- V7 — Forecasting (PRD §11)
--
-- Three tables, and the relationship between them is the point of the design:
--
--   model_runs         what was trained, and how wrong it was measured to be
--   forecasts          what was predicted, citing the run that predicted it
--   forecast_accuracy  what actually happened, and the error that resulted
--
-- The PRD's exit criterion is that confidence is *derived from measured error,
-- not asserted*. That is only possible if the error is a stored number rather
-- than a constant in code — so `model_runs` holds the MAE and MAPE measured on
-- a temporal holdout, every forecast points at the run it came from, and the
-- confidence a caller sees is computed from that run's measured error for that
-- exact metric and horizon.
--
-- `forecast_accuracy` closes the loop. Without it the platform could report a
-- confidence forever without ever checking whether it was earned, which is
-- indistinguishable from making the number up.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Model runs
-- -----------------------------------------------------------------------------
CREATE TABLE model_runs (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID         NOT NULL UNIQUE,

    model_name          VARCHAR(64)  NOT NULL,
    -- Semantic, not a hash: a human reading a forecast needs to know which
    -- model produced it, and "v3" is a fact they can act on.
    model_version       VARCHAR(32)  NOT NULL,
    algorithm           VARCHAR(64)  NOT NULL,

    -- The temporal holdout. Stored explicitly because "we evaluated it" is not
    -- a claim anyone should accept without knowing on what: a model tested on
    -- data it trained on will look excellent and forecast nothing.
    trained_from        TIMESTAMPTZ  NOT NULL,
    trained_to          TIMESTAMPTZ  NOT NULL,
    evaluated_from      TIMESTAMPTZ  NOT NULL,
    evaluated_to        TIMESTAMPTZ  NOT NULL,

    training_rows       INTEGER      NOT NULL,
    evaluation_rows     INTEGER      NOT NULL,

    -- Feature names, so a stored forecast can be explained after the code that
    -- produced it has moved on.
    features            JSONB        NOT NULL DEFAULT '[]'::jsonb,
    hyperparameters     JSONB        NOT NULL DEFAULT '{}'::jsonb,

    status              VARCHAR(24)  NOT NULL DEFAULT 'EVALUATED',
    notes               VARCHAR(1000),

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT ck_model_runs_status CHECK (status IN ('TRAINING', 'EVALUATED', 'ACTIVE', 'RETIRED')),
    -- Evaluation must not overlap training, or the measured error is fiction.
    CONSTRAINT ck_model_runs_holdout CHECK (evaluated_from >= trained_to),
    CONSTRAINT ck_model_runs_train_order CHECK (trained_to > trained_from),
    CONSTRAINT ck_model_runs_eval_order CHECK (evaluated_to > evaluated_from),
    CONSTRAINT ck_model_runs_rows CHECK (training_rows > 0 AND evaluation_rows > 0)
);

-- Exactly one active run per model. Two would make "which model made this
-- prediction" ambiguous at the moment it matters most.
CREATE UNIQUE INDEX uq_model_runs_active ON model_runs (model_name) WHERE status = 'ACTIVE';
CREATE INDEX idx_model_runs_name_created ON model_runs (model_name, created_at DESC);

-- -----------------------------------------------------------------------------
-- Measured error, per metric and per horizon
--
-- Separate from model_runs because a single run is evaluated across five
-- horizons and several metrics, and its 15-minute error is not its 6-hour
-- error. Collapsing them into one number would let a model that is excellent
-- at 15 minutes lend unearned confidence to its 6-hour predictions.
-- -----------------------------------------------------------------------------
CREATE TABLE model_metrics (
    id                  BIGSERIAL PRIMARY KEY,
    model_run_id        BIGINT       NOT NULL REFERENCES model_runs (id) ON DELETE CASCADE,

    target_metric       VARCHAR(32)  NOT NULL,
    horizon_minutes     INTEGER      NOT NULL,

    -- Mean absolute error, in the target's own units.
    mae                 NUMERIC(12, 4) NOT NULL,
    -- Mean absolute percentage error. Nullable: MAPE is undefined when actuals
    -- are near zero, and reporting a fabricated percentage there would be worse
    -- than reporting none.
    mape                NUMERIC(8, 4),
    rmse                NUMERIC(12, 4),

    -- The naive baseline this was measured against — persistence, i.e.
    -- "tomorrow looks like today". A model that cannot beat it has not earned
    -- its complexity, and storing the comparison keeps that honest.
    baseline_mae        NUMERIC(12, 4),

    sample_count        INTEGER      NOT NULL,
    computed_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_model_metrics UNIQUE (model_run_id, target_metric, horizon_minutes),
    CONSTRAINT ck_model_metrics_horizon CHECK (horizon_minutes IN (15, 30, 60, 180, 360)),
    CONSTRAINT ck_model_metrics_target CHECK (target_metric IN (
        'occupancy_ratio', 'average_speed_kph', 'vehicle_count', 'aqi', 'risk_score')),
    CONSTRAINT ck_model_metrics_errors CHECK (mae >= 0 AND (rmse IS NULL OR rmse >= 0)),
    CONSTRAINT ck_model_metrics_samples CHECK (sample_count > 0)
);

CREATE INDEX idx_model_metrics_lookup ON model_metrics (model_run_id, target_metric, horizon_minutes);

-- -----------------------------------------------------------------------------
-- Forecasts
-- -----------------------------------------------------------------------------
CREATE TABLE forecasts (
    id                  BIGSERIAL PRIMARY KEY,
    uid                 UUID         NOT NULL UNIQUE,

    zone_id             BIGINT       NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    model_run_id        BIGINT       NOT NULL REFERENCES model_runs (id) ON DELETE RESTRICT,

    target_metric       VARCHAR(32)  NOT NULL,
    horizon_minutes     INTEGER      NOT NULL,

    -- When the prediction was made, and the moment it describes. Both are
    -- needed: without issued_at a forecast cannot be scored fairly after the
    -- fact, because there is no way to know what was knowable when it was made.
    issued_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    target_time         TIMESTAMPTZ  NOT NULL,

    -- The last observed window the prediction was based on.
    based_on_window     TIMESTAMPTZ  NOT NULL,

    predicted_value     NUMERIC(12, 4) NOT NULL,
    -- Interval derived from the run's measured error, not from the model's own
    -- opinion of itself.
    lower_bound         NUMERIC(12, 4),
    upper_bound         NUMERIC(12, 4),
    confidence          NUMERIC(5, 4) NOT NULL,

    risk_level          VARCHAR(16),

    -- What drove this prediction, for the explanation the UI shows (PRD §11,
    -- §15). Stored rather than recomputed so an old forecast can still be
    -- explained after the feature code changes.
    contributing_factors JSONB       NOT NULL DEFAULT '[]'::jsonb,

    demo_data           BOOLEAN      NOT NULL DEFAULT TRUE,

    CONSTRAINT uq_forecasts_zone_target UNIQUE (zone_id, target_metric, horizon_minutes, issued_at),
    CONSTRAINT ck_forecasts_horizon CHECK (horizon_minutes IN (15, 30, 60, 180, 360)),
    CONSTRAINT ck_forecasts_target CHECK (target_metric IN (
        'occupancy_ratio', 'average_speed_kph', 'vehicle_count', 'aqi', 'risk_score')),
    CONSTRAINT ck_forecasts_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_forecasts_bounds CHECK (
        lower_bound IS NULL OR upper_bound IS NULL OR lower_bound <= upper_bound),
    CONSTRAINT ck_forecasts_risk CHECK (risk_level IS NULL OR risk_level IN (
        'NORMAL', 'MODERATE', 'HIGH', 'CRITICAL')),
    -- A prediction about the past is not a prediction.
    CONSTRAINT ck_forecasts_time_order CHECK (target_time > based_on_window)
);

-- The read path: the newest forecast for a zone and metric across horizons.
CREATE INDEX idx_forecasts_zone_lookup
    ON forecasts (zone_id, target_metric, issued_at DESC);
CREATE INDEX idx_forecasts_target_time ON forecasts (target_time);
CREATE INDEX brin_forecasts_issued ON forecasts USING BRIN (issued_at);

-- -----------------------------------------------------------------------------
-- Accuracy against actuals
--
-- Written once the forecast's target time has passed and the real window exists.
-- This is the table that makes "prediction accuracy is tracked over time" a
-- query rather than a claim.
-- -----------------------------------------------------------------------------
CREATE TABLE forecast_accuracy (
    id                  BIGSERIAL PRIMARY KEY,
    forecast_id         BIGINT       NOT NULL REFERENCES forecasts (id) ON DELETE CASCADE,
    model_run_id        BIGINT       NOT NULL REFERENCES model_runs (id) ON DELETE RESTRICT,

    zone_id             BIGINT       NOT NULL REFERENCES zones (id) ON DELETE RESTRICT,
    target_metric       VARCHAR(32)  NOT NULL,
    horizon_minutes     INTEGER      NOT NULL,
    target_time         TIMESTAMPTZ  NOT NULL,

    predicted_value     NUMERIC(12, 4) NOT NULL,
    actual_value        NUMERIC(12, 4) NOT NULL,
    absolute_error      NUMERIC(12, 4) NOT NULL,
    -- Null when the actual is at or near zero; a percentage error against zero
    -- is meaningless and averaging it in would corrupt every summary it enters.
    percentage_error    NUMERIC(10, 4),

    -- Whether the reading fell inside the interval the forecast advertised.
    -- Aggregated over time this is the honest test of whether the confidence
    -- meant anything.
    within_bounds       BOOLEAN,

    scored_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_forecast_accuracy_forecast UNIQUE (forecast_id),
    CONSTRAINT ck_forecast_accuracy_error CHECK (absolute_error >= 0)
);

CREATE INDEX idx_forecast_accuracy_run ON forecast_accuracy (model_run_id, target_metric, horizon_minutes);
CREATE INDEX idx_forecast_accuracy_zone_time ON forecast_accuracy (zone_id, target_time DESC);
CREATE INDEX brin_forecast_accuracy_target ON forecast_accuracy USING BRIN (target_time);
