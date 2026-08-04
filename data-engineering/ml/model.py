"""Baseline forecast model (PRD §11, and §15 of the execution prompt).

Ridge regression, deliberately. The PRD asks for a *measured baseline before
complexity*, and that is not a compromise here — traffic at a five-minute grain
is dominated by its own recent history and by the time of day, both of which a
linear model with lag and calendar features captures directly. A gradient
boosting ensemble would fit better on paper; it would also be harder to explain,
and PRD §11 requires every forecast to name its contributing factors. A linear
model's coefficients *are* that explanation.

The ridge penalty exists because the lag features are strongly collinear by
construction — occupancy five minutes ago and fifteen minutes ago move together
— and ordinary least squares on collinear inputs produces huge cancelling
coefficients that swing wildly between refits. That instability would surface to
users as a forecast that changes its story every time the model retrains.

One model per (target metric, horizon). A single model predicting all horizons
would have to learn that its own error grows with distance, which it cannot
express; separate fits let each horizon carry its own measured error, which is
what the confidence calculation needs.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Sequence

import numpy as np


@dataclass(slots=True)
class RidgeModel:
    """A fitted linear model with its feature order recorded.

    `feature_names` is stored rather than assumed: a prediction built from
    features in a different order than training would be silently, confidently
    wrong, and nothing downstream would catch it.
    """

    feature_names: tuple[str, ...]
    coefficients: np.ndarray
    intercept: float
    # Standardisation constants from training. Applied identically at predict
    # time — recomputing them from prediction data would leak and would shift
    # the model's meaning between calls.
    means: np.ndarray
    scales: np.ndarray
    alpha: float

    def predict_one(self, features: dict[str, float]) -> float:
        vector = np.array([features[name] for name in self.feature_names], dtype=float)
        scaled = (vector - self.means) / self.scales
        return float(scaled @ self.coefficients + self.intercept)

    def predict(self, rows: Sequence[dict[str, float]]) -> np.ndarray:
        if not rows:
            return np.array([])
        matrix = np.array(
            [[row[name] for name in self.feature_names] for row in rows], dtype=float
        )
        scaled = (matrix - self.means) / self.scales
        return scaled @ self.coefficients + self.intercept

    def contributions(self, features: dict[str, float], top: int = 4) -> list[dict[str, float | str]]:
        """The features that moved this prediction furthest from the average.

        Reported as (standardised feature value x coefficient), which is the
        signed amount each feature added to or removed from the intercept. That
        is the honest answer to "why did you predict this" for a linear model:
        it is the actual arithmetic, not a post-hoc rationalisation.
        """
        vector = np.array([features[name] for name in self.feature_names], dtype=float)
        scaled = (vector - self.means) / self.scales
        effects = scaled * self.coefficients

        order = np.argsort(np.abs(effects))[::-1][:top]
        return [
            {
                "feature": self.feature_names[i],
                "value": round(float(vector[i]), 4),
                "effect": round(float(effects[i]), 4),
                "direction": "increases" if effects[i] > 0 else "decreases",
            }
            for i in order
            if abs(effects[i]) > 1e-9
        ]


def fit_ridge(
    rows: Sequence[dict[str, float]],
    labels: Sequence[float],
    feature_names: Sequence[str],
    *,
    alpha: float = 1.0,
) -> RidgeModel:
    """Fit by the normal equations.

    Closed-form rather than iterative: with fifteen features the matrix is
    15x15, so there is nothing to gain from gradient descent and a great deal to
    lose in reproducibility — the same data must produce the same model, or
    yesterday's measured error says nothing about today's predictions.
    """
    if len(rows) != len(labels):
        raise ValueError("rows and labels must be the same length")
    if len(rows) <= len(feature_names):
        raise ValueError(
            f"need more rows ({len(rows)}) than features ({len(feature_names)}) to fit"
        )

    names = tuple(feature_names)
    matrix = np.array([[row[name] for name in names] for row in rows], dtype=float)
    target = np.array(labels, dtype=float)

    means = matrix.mean(axis=0)
    scales = matrix.std(axis=0)
    # A constant feature has zero spread; dividing by it yields NaN and poisons
    # every prediction. Substituting 1.0 leaves the centred column at zero, so
    # the feature simply contributes nothing — which is correct, because a
    # feature that never varies carries no information.
    scales = np.where(scales < 1e-12, 1.0, scales)
    scaled = (matrix - means) / scales

    # The intercept is not penalised: shrinking it toward zero would bias every
    # prediction toward zero rather than toward the mean.
    intercept = float(target.mean())
    centred_target = target - intercept

    gram = scaled.T @ scaled + alpha * np.eye(scaled.shape[1])
    coefficients = np.linalg.solve(gram, scaled.T @ centred_target)

    return RidgeModel(
        feature_names=names,
        coefficients=coefficients,
        intercept=intercept,
        means=means,
        scales=scales,
        alpha=alpha,
    )


@dataclass(slots=True)
class Evaluation:
    """Measured error on held-out data."""

    mae: float
    rmse: float
    mape: float | None
    baseline_mae: float
    sample_count: int
    # Residual spread, used to build prediction intervals from measured error
    # rather than from an assumption about the model.
    residual_std: float
    residuals: list[float] = field(default_factory=list, repr=False)

    @property
    def beats_baseline(self) -> bool:
        return self.mae < self.baseline_mae


def evaluate(
    model: RidgeModel,
    rows: Sequence[dict[str, float]],
    labels: Sequence[float],
    *,
    persistence_feature: str,
) -> Evaluation:
    """Score a model against held-out data and against doing nothing.

    `persistence_feature` names the "no change" prediction — the most recent
    observation. Every forecast is compared against it because a model that
    cannot beat persistence has not earned its existence, and reporting an MAE
    without that comparison lets a useless model look precise.
    """
    if not rows:
        raise ValueError("cannot evaluate on an empty set")

    predictions = model.predict(rows)
    actuals = np.array(labels, dtype=float)
    errors = np.abs(predictions - actuals)

    naive = np.array([row[persistence_feature] for row in rows], dtype=float)
    baseline_errors = np.abs(naive - actuals)

    # MAPE is undefined near zero and explodes just above it. Rows with a
    # near-zero actual are excluded rather than clamped: a fabricated percentage
    # would corrupt the average it enters, and reporting MAPE over a subset is
    # honest as long as the subset is stated.
    significant = np.abs(actuals) > 1e-6
    mape = (
        float(np.mean(np.abs((predictions[significant] - actuals[significant])
                             / actuals[significant])) * 100)
        if significant.sum() > 0
        else None
    )

    residuals = predictions - actuals

    return Evaluation(
        mae=float(np.mean(errors)),
        rmse=float(np.sqrt(np.mean(residuals**2))),
        mape=mape,
        baseline_mae=float(np.mean(baseline_errors)),
        sample_count=len(rows),
        residual_std=float(np.std(residuals)),
        residuals=residuals.tolist(),
    )


def confidence_from_error(predicted: float, mae: float, *, typical_scale: float) -> float:
    """Turn a measured error into a confidence between 0 and 1.

    This is the calculation PRD §11's exit criterion is about: confidence must
    be *derived from measured error*, never asserted. The number here comes
    entirely from the MAE the model achieved on held-out data for this exact
    metric and horizon — a 6-hour forecast is less confident than a 15-minute
    one because it was measured to be worse, not because someone decided it
    should look that way.

    `typical_scale` normalises the error into the target's own units, so an MAE
    of 0.05 on occupancy (scale ~1.0) and an MAE of 5 on AQI (scale ~150) are
    comparable. Confidence is capped at 0.95: a model is never certain, and
    showing 100% would be a claim no measurement can support.
    """
    if typical_scale <= 0:
        raise ValueError("typical_scale must be positive")

    relative_error = mae / typical_scale
    confidence = max(0.0, 1.0 - relative_error)
    return round(min(0.95, confidence), 4)


def prediction_interval(predicted: float, residual_std: float, *, z: float = 1.96) -> tuple[float, float]:
    """A 95% interval from the measured residual spread.

    Built from residuals observed on the holdout rather than from the model's
    internal variance estimate, which assumes the errors are normal and
    homoscedastic — neither of which traffic residuals are.
    """
    margin = z * residual_std
    return predicted - margin, predicted + margin
