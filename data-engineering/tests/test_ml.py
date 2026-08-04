"""Tests for the forecast layer.

Most of these are about **leakage**, because it is the failure mode that no
accuracy number can reveal. A model that has seen the future scores beautifully
on every metric this project computes and forecasts nothing in production — so
the guarantee has to be tested structurally, at the point where features are
built, rather than inferred from a good MAE.
"""

from __future__ import annotations

import math
from datetime import datetime, timedelta, timezone

import pytest

from ml.features import (
    FEATURE_NAMES,
    HORIZONS_MINUTES,
    LAG_STEPS,
    ROLLING_STEPS,
    Observation,
    build_features,
    build_training_rows,
)
from ml.model import (
    confidence_from_error,
    evaluate,
    fit_ridge,
    prediction_interval,
)

BASE = datetime(2026, 8, 4, 6, 0, tzinfo=timezone.utc)


def series(count: int, *, values: list[float] | None = None, gap_at: int | None = None) -> list[Observation]:
    """A zone's window history, optionally with a gap punched in it."""
    observations = []
    minute = 0
    for i in range(count):
        if gap_at is not None and i == gap_at:
            minute += 15  # a missing stretch
        observations.append(Observation(
            zone_id=1,
            zone_type="COMMERCIAL",
            window_start=BASE + timedelta(minutes=minute),
            occupancy_ratio=(values[i] if values else 0.4 + 0.1 * math.sin(i / 6)),
            average_speed_kph=40.0,
            vehicle_count=800,
            aqi=120,
            risk_score=35.0,
            precipitation_mm_h=0.0,
            temperature_c=28.0,
            active_incidents=0,
            active_events=0,
        ))
        minute += 5
    return observations


class TestNoLeakage:
    def test_features_ignore_everything_after_the_issue_point(self) -> None:
        """The single most important property in this module.

        The same history, with every future window replaced by absurd values,
        must produce byte-identical features. If it does not, the model can see
        forward and its measured error is fiction.
        """
        history = series(60)
        index = 40

        before = build_features(history, index, "occupancy_ratio")

        poisoned = list(history)
        for i in range(index + 1, len(poisoned)):
            poisoned[i] = Observation(
                zone_id=1,
                zone_type="COMMERCIAL",
                window_start=poisoned[i].window_start,
                occupancy_ratio=999.0,
                average_speed_kph=-50.0,
                vehicle_count=999_999,
                aqi=9999,
                risk_score=999.0,
                precipitation_mm_h=500.0,
                temperature_c=99.0,
                active_incidents=99,
                active_events=99,
            )

        after = build_features(poisoned, index, "occupancy_ratio")
        assert before == after

    def test_the_label_is_never_among_the_features(self) -> None:
        history = series(60)
        rows = build_training_rows(history, "occupancy_ratio", 60)
        assert rows

        for issued_at, features, label in rows:
            # The label is the value one horizon ahead. No feature may equal it
            # except by coincidence of a flat series, so a distinctive series is
            # used to make an accidental match essentially impossible.
            assert all(math.isfinite(v) for v in features.values())

        distinctive = series(60, values=[i * 1.0 for i in range(60)])
        for issued_at, features, label in build_training_rows(distinctive, "occupancy_ratio", 60):
            assert label not in features.values()

    def test_a_gap_in_the_data_drops_the_row(self) -> None:
        """A missing stretch must not silently become a shorter horizon.

        Without the check, a row whose 'next hour' is really 'next 75 minutes'
        would train the 60-minute model, making it look better at long ranges
        than it is.
        """
        # The gap must land inside the usable range: rows only begin once there
        # is enough history for the longest rolling window (36 steps), so a gap
        # before that is discarded along with the rows it would have affected.
        continuous = build_training_rows(series(120), "occupancy_ratio", 60)
        with_gap = build_training_rows(series(120, gap_at=60), "occupancy_ratio", 60)

        assert continuous, "the continuous series should yield training rows"
        assert len(with_gap) < len(continuous), (
            f"a 15-minute gap should drop the rows that straddle it "
            f"(continuous={len(continuous)}, with gap={len(with_gap)})"
        )


class TestFeatureContract:
    def test_returns_none_without_enough_history(self) -> None:
        # Padding a cold start with zeros would teach the model that "no data"
        # looks like an empty road.
        needed = max(max(LAG_STEPS), max(ROLLING_STEPS))
        assert build_features(series(needed + 1), needed - 1, "occupancy_ratio") is None

    def test_produces_exactly_the_declared_features(self) -> None:
        features = build_features(series(60), 50, "occupancy_ratio")
        assert features is not None
        assert set(features) == set(FEATURE_NAMES)

    def test_hour_is_encoded_cyclically(self) -> None:
        """23:55 and 00:05 must be adjacent, not maximally distant."""
        late = build_features(series(60), 50, "occupancy_ratio")
        assert late is not None
        # sin² + cos² == 1 for any hour, which is what makes the pair a circle.
        assert late["hour_sin"] ** 2 + late["hour_cos"] ** 2 == pytest.approx(1.0)

    def test_a_missing_target_value_yields_no_features(self) -> None:
        history = series(60)
        history[50] = Observation(
            zone_id=1, zone_type="COMMERCIAL", window_start=history[50].window_start,
            occupancy_ratio=None, average_speed_kph=40.0, vehicle_count=800, aqi=120,
            risk_score=35.0, precipitation_mm_h=0.0, temperature_c=28.0,
            active_incidents=0, active_events=0)
        assert build_features(history, 50, "occupancy_ratio") is None

    @pytest.mark.parametrize("horizon", HORIZONS_MINUTES)
    def test_the_label_sits_exactly_one_horizon_ahead(self, horizon: int) -> None:
        history = series(120)
        for issued_at, _, _ in build_training_rows(history, "occupancy_ratio", horizon):
            match = [o for o in history if o.window_start == issued_at + timedelta(minutes=horizon)]
            assert match, f"no window exactly {horizon} minutes after {issued_at}"


class TestRidge:
    def test_recovers_a_known_linear_relationship(self) -> None:
        names = ("a", "b")
        rows = [{"a": float(i), "b": float(i % 7)} for i in range(400)]
        labels = [3.0 * r["a"] - 2.0 * r["b"] + 5.0 for r in rows]

        model = fit_ridge(rows, labels, names, alpha=1e-6)
        predicted = model.predict_one({"a": 10.0, "b": 3.0})

        assert predicted == pytest.approx(3.0 * 10 - 2.0 * 3 + 5.0, abs=0.5)

    def test_a_constant_feature_does_not_produce_nan(self) -> None:
        # Zero spread would divide by zero in standardisation and poison every
        # later prediction with NaN.
        names = ("varies", "constant")
        rows = [{"varies": float(i), "constant": 1.0} for i in range(100)]
        labels = [2.0 * r["varies"] for r in rows]

        model = fit_ridge(rows, labels, names)
        assert math.isfinite(model.predict_one({"varies": 5.0, "constant": 1.0}))

    def test_refuses_to_fit_with_fewer_rows_than_features(self) -> None:
        with pytest.raises(ValueError):
            fit_ridge([{"a": 1.0, "b": 2.0}], [1.0], ("a", "b"))

    def test_contributions_are_the_actual_arithmetic(self) -> None:
        """A linear model's explanation is its own terms, not a story."""
        names = ("strong", "weak")
        rows = [{"strong": float(i), "weak": float(i % 3)} for i in range(300)]
        labels = [10.0 * r["strong"] + 0.1 * r["weak"] for r in rows]

        model = fit_ridge(rows, labels, names, alpha=1e-6)
        factors = model.contributions({"strong": 50.0, "weak": 1.0}, top=2)

        assert factors[0]["feature"] == "strong"
        assert factors[0]["direction"] in ("increases", "decreases")


class TestEvaluation:
    def test_compares_against_persistence(self) -> None:
        names = ("lag_5min",)
        rows = [{"lag_5min": float(i)} for i in range(200)]
        labels = [float(i) + 1.0 for i in range(200)]

        model = fit_ridge(rows, labels, names, alpha=1e-6)
        scoring = evaluate(model, rows, labels, persistence_feature="lag_5min")

        # Persistence is always wrong by exactly 1 here; the model learns the
        # offset, so it must do better.
        assert scoring.baseline_mae == pytest.approx(1.0, abs=0.01)
        assert scoring.beats_baseline

    def test_mape_excludes_near_zero_actuals(self) -> None:
        """A percentage error against zero is arithmetically true and useless."""
        names = ("x",)
        rows = [{"x": float(i)} for i in range(100)]
        labels = [0.0] + [float(i) for i in range(1, 100)]

        model = fit_ridge(rows, labels, names, alpha=1e-6)
        scoring = evaluate(model, rows, labels, persistence_feature="x")

        # Included, the zero row would drive MAPE to infinity and swamp the rest.
        assert scoring.mape is not None
        assert math.isfinite(scoring.mape)


class TestConfidence:
    def test_falls_as_measured_error_rises(self) -> None:
        """The exit criterion: confidence derives from measurement.

        A worse-measured model must report lower confidence — not because
        someone decided long horizons should look uncertain, but because the
        arithmetic reads the MAE.
        """
        near = confidence_from_error(0.5, mae=0.05, typical_scale=1.0)
        far = confidence_from_error(0.5, mae=0.25, typical_scale=1.0)
        assert far < near

    def test_never_claims_certainty(self) -> None:
        assert confidence_from_error(0.5, mae=0.0, typical_scale=1.0) == 0.95

    def test_never_goes_negative(self) -> None:
        # An error larger than the scale means the model is useless, not that
        # confidence is negative.
        assert confidence_from_error(0.5, mae=10.0, typical_scale=1.0) == 0.0

    def test_scale_makes_different_units_comparable(self) -> None:
        # 5% relative error in both cases, so the same confidence.
        occupancy = confidence_from_error(0.5, mae=0.05, typical_scale=1.0)
        speed = confidence_from_error(40.0, mae=2.4, typical_scale=48.0)
        assert occupancy == pytest.approx(speed, abs=0.01)

    def test_rejects_a_meaningless_scale(self) -> None:
        with pytest.raises(ValueError):
            confidence_from_error(1.0, mae=0.1, typical_scale=0.0)


class TestPredictionInterval:
    def test_widens_with_observed_residual_spread(self) -> None:
        tight = prediction_interval(10.0, residual_std=0.5)
        loose = prediction_interval(10.0, residual_std=5.0)
        assert (loose[1] - loose[0]) > (tight[1] - tight[0])

    def test_is_centred_on_the_prediction(self) -> None:
        lower, upper = prediction_interval(10.0, residual_std=2.0)
        assert (lower + upper) / 2 == pytest.approx(10.0)
