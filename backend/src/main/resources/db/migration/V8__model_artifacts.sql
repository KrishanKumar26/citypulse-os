-- =============================================================================
-- V8 — Fitted model parameters
--
-- V7 stored what a model run measured but not what it learned, so a forecast
-- could not be produced without refitting from scratch. These columns close
-- that: the parameters live on `model_metrics`, at the same grain as the error,
-- because each (target metric, horizon) is genuinely its own fitted model and
-- its own measured error.
--
-- Stored as JSON rather than a binary artefact deliberately. A linear model is
-- fifteen numbers and a feature name list; keeping it readable means a stored
-- forecast can be re-derived and audited years later without the training code,
-- which a pickle would make impossible the first time a library version moved.
-- =============================================================================

ALTER TABLE model_metrics
    -- Coefficients in the same order as model_runs.features. The pairing is
    -- what makes the model interpretable; a coefficient without its feature
    -- name is a number nobody can check.
    ADD COLUMN coefficients   JSONB,
    ADD COLUMN intercept      NUMERIC(14, 6),
    -- Standardisation constants from training. Applied unchanged at predict
    -- time — recomputing them from live data would shift the model's meaning
    -- between calls and make yesterday's measured error inapplicable to today.
    ADD COLUMN feature_means  JSONB,
    ADD COLUMN feature_scales JSONB,
    -- Spread of the holdout residuals. Prediction intervals are built from this
    -- observed spread rather than from a normality assumption the residuals do
    -- not satisfy.
    ADD COLUMN residual_std   NUMERIC(12, 4);

COMMENT ON COLUMN model_metrics.coefficients IS
    'Fitted weights, ordered to match model_runs.features';
COMMENT ON COLUMN model_metrics.residual_std IS
    'Observed standard deviation of holdout residuals; the basis for prediction intervals';
