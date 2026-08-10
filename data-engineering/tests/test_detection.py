"""Anomaly detection behaviour (PRD §13, §15).

The properties asserted here are the ones a good measured precision could still
hide: that the detector declines when it has no basis, that it does not learn
its own anomalies as normal, and that a metric which merely wobbles is not
reported as unusual.
"""

from __future__ import annotations

import math
from datetime import datetime, timezone

import pytest

from intelligence.detection import (
    MIN_BASELINE_SAMPLES,
    MIN_RELATIVE_CHANGE,
    THRESHOLD_LOW,
    AnomalyType,
    Baseline,
    Detection,
    InsufficientData,
    Severity,
    detect,
    detect_sustained,
    hour_of_week,
    learn_baseline,
    median,
    median_absolute_deviation,
)


def a_baseline(median_value: float = 100.0, mad: float = 5.0, samples: int = 100) -> Baseline:
    return Baseline(
        metric="vehicle_count",
        hour_of_week=30,
        median=median_value,
        mad=mad,
        p10=median_value - 10,
        p90=median_value + 10,
        sample_count=samples,
    )


class TestRobustStatistics:
    def test_median_ignores_an_extreme_value(self) -> None:
        # The property the whole design rests on: a baseline learned from
        # history containing anomalies must not absorb them.
        ordinary = [10.0, 11.0, 10.5, 9.5, 10.2]
        with_spike = ordinary + [500.0]

        assert median(with_spike) == pytest.approx(median(ordinary), abs=0.6)
        # A mean would have moved by roughly 80.
        assert abs(sum(with_spike) / len(with_spike) - sum(ordinary) / len(ordinary)) > 50

    def test_mad_ignores_an_extreme_value(self) -> None:
        ordinary = [10.0, 11.0, 10.5, 9.5, 10.2]
        with_spike = ordinary + [500.0]
        assert median_absolute_deviation(with_spike) < 2.0

    def test_learning_an_empty_bucket_yields_nothing(self) -> None:
        # A baseline of zero would make every real reading look like an enormous
        # spike, which is worse than having no baseline.
        assert learn_baseline("vehicle_count", 0, []) is None
        assert learn_baseline("vehicle_count", 0, [None, None]) is None


class TestHourOfWeek:
    def test_covers_the_whole_week(self) -> None:
        monday = datetime(2026, 8, 3, 0, 30, tzinfo=timezone.utc)
        # 0 = Monday 00:00 local; the seeded cities are UTC+5:30.
        assert 0 <= hour_of_week(monday) <= 167

    def test_distinguishes_the_same_hour_on_different_days(self) -> None:
        # Tuesday 09:00 and Sunday 09:00 are not the same city, and a detector
        # that treats them alike flags every quiet weekend morning as a drop.
        tuesday = datetime(2026, 8, 4, 3, 30, tzinfo=timezone.utc)
        sunday = datetime(2026, 8, 9, 3, 30, tzinfo=timezone.utc)
        assert hour_of_week(tuesday) != hour_of_week(sunday)


class TestInsufficientData:
    def test_declines_on_a_thin_baseline(self) -> None:
        outcome = detect(500.0, a_baseline(samples=MIN_BASELINE_SAMPLES - 1))

        # PRD §15: "we cannot tell" and "nothing is wrong" are different facts.
        assert isinstance(outcome, InsufficientData)
        assert str(MIN_BASELINE_SAMPLES) in outcome.reason

    def test_declining_is_a_distinct_type_from_finding_nothing(self) -> None:
        thin = detect(500.0, a_baseline(samples=3))
        normal = detect(101.0, a_baseline())

        assert isinstance(thin, InsufficientData)
        assert isinstance(normal, Detection)
        assert normal.is_anomaly is False

    def test_a_thin_baseline_declines_even_for_an_extreme_value(self) -> None:
        # The temptation is to make an exception for obviously extreme readings.
        # Resisted: with three samples there is no basis for "obviously".
        assert isinstance(detect(1_000_000.0, a_baseline(samples=2)), InsufficientData)


class TestDetection:
    def test_flags_a_clear_spike(self) -> None:
        outcome = detect(400.0, a_baseline())

        assert isinstance(outcome, Detection)
        assert outcome.is_anomaly
        assert outcome.anomaly_type is AnomalyType.SPIKE
        assert outcome.percent_change == pytest.approx(300.0, abs=1)

    def test_flags_a_clear_drop(self) -> None:
        outcome = detect(20.0, a_baseline())
        assert isinstance(outcome, Detection)
        assert outcome.anomaly_type is AnomalyType.DROP

    def test_ignores_ordinary_variation(self) -> None:
        outcome = detect(103.0, a_baseline())
        assert isinstance(outcome, Detection)
        assert outcome.is_anomaly is False

    def test_requires_material_change_as_well_as_statistical_oddity(self) -> None:
        """Both tests must pass, not either.

        A metric that never varies makes a tiny absolute change statistically
        enormous. Flagging that produces a feed of technically-unusual,
        operationally-irrelevant events, which is a feed that gets muted.
        """
        # 5.1 sigma out, but only 15% from normal — below the materiality bar.
        steady = a_baseline(median_value=100.0, mad=2.0)
        outcome = detect(115.0, steady)

        assert isinstance(outcome, Detection)
        assert outcome.deviation_score > THRESHOLD_LOW
        assert outcome.is_anomaly is False
        assert "only" in outcome.explanation

    def test_material_and_statistical_together_do_fire(self) -> None:
        outcome = detect(160.0, a_baseline(median_value=100.0, mad=2.0))
        assert isinstance(outcome, Detection)
        assert outcome.is_anomaly

    def test_a_flat_history_cannot_make_everything_extreme(self) -> None:
        # A MAD of zero would divide by zero and make any deviation infinite.
        outcome = detect(101.0, a_baseline(mad=0.0))
        assert isinstance(outcome, Detection)
        assert math.isfinite(outcome.deviation_score)
        assert outcome.is_anomaly is False

    @pytest.mark.parametrize(
        "observed,expected",
        [(160.0, Severity.LOW), (190.0, Severity.MEDIUM), (250.0, Severity.HIGH), (300.0, Severity.CRITICAL)],
    )
    def test_severity_rises_with_deviation(self, observed: float, expected: Severity) -> None:
        # mad well above the 2% floor, so the band boundaries are the thing
        # under test rather than the floor.
        outcome = detect(observed, a_baseline(median_value=100.0, mad=10.0))
        assert isinstance(outcome, Detection)
        assert outcome.severity is expected


class TestExplanation:
    def test_states_the_situation_not_the_method(self) -> None:
        """The line a duty officer reads on a critical row.

        It said "20.0 standard deviations below the normal 43.23", then a
        plainer sentence describing the same thing — how the detection was made.
        Neither answers the question being asked, which is how bad this is
        against what is normal. The evidence is still on the row and still shown
        in the footnote; it is not the message.
        """
        outcome = detect(
            1.62, a_baseline(median_value=0.53, mad=0.03, samples=12),
            metric_label="Roads", metric="occupancy_ratio",
        )
        assert isinstance(outcome, Detection)
        assert outcome.explanation == (
            "Roads are 3.1x as full as usual for this time of day."
        )
        for method in ("standard deviation", "baseline", "past readings", "variation"):
            assert method not in outcome.explanation.lower()

    def test_compares_downward_in_words_someone_would_say(self) -> None:
        # English does not compare up and down with the same words. "3.1x as
        # full" is right; "0.4x as full" is not something anyone says.
        outcome = detect(
            15.7, a_baseline(median_value=42.8, mad=2.0, samples=12),
            metric_label="Speed", metric="average_speed_kph",
        )
        assert isinstance(outcome, Detection)
        assert outcome.explanation == (
            "Traffic is moving at 37% of the usual speed for this time of day."
        )

    def test_speaks_in_multiples_so_the_units_cannot_disagree(self) -> None:
        """The bug this also fixes.

        The card scales occupancy by 100 to show "162% of capacity" while the
        stored sentence carried the raw 1.62, so one card presented the same
        fact as two different numbers. A ratio has no units to disagree about.
        """
        outcome = detect(
            1.62, a_baseline(median_value=0.53, mad=0.03, samples=12),
            metric_label="Roads", metric="occupancy_ratio",
        )
        assert isinstance(outcome, Detection)
        assert "1.62" not in outcome.explanation
        assert "0.53" not in outcome.explanation

    def test_says_so_when_there_is_nothing_to_compare_against(self) -> None:
        from intelligence.detection import explain

        sentence = explain(
            label="Roads", observed=1.62, baseline_median=0.0, samples=0,
            direction="above", percent_change=None, metric="occupancy_ratio",
        )
        assert "no usual level" in sentence

    def test_explains_a_non_anomaly_too(self) -> None:
        # A user who asks why nothing fired deserves an answer.
        outcome = detect(101.0, a_baseline())
        assert isinstance(outcome, Detection)
        assert outcome.explanation


class TestSustainedShift:
    def test_flags_a_level_that_moved_and_stayed(self) -> None:
        baseline = a_baseline(median_value=100.0, mad=5.0)
        outcome = detect_sustained([140.0] * 6, baseline)

        assert isinstance(outcome, Detection)
        assert outcome.anomaly_type is AnomalyType.SUSTAINED_SHIFT

    def test_ignores_a_single_excursion(self) -> None:
        baseline = a_baseline(median_value=100.0, mad=5.0)
        # One window out, five ordinary: a spike, not a shift.
        assert detect_sustained([100.0, 100.0, 100.0, 100.0, 100.0, 300.0], baseline) is None

    def test_ignores_alternating_noise(self) -> None:
        # A set that swings either side of normal is noise, not a shift.
        baseline = a_baseline(median_value=100.0, mad=5.0)
        assert detect_sustained([140.0, 60.0, 140.0, 60.0, 140.0, 60.0], baseline) is None

    def test_returns_none_without_enough_recent_history(self) -> None:
        # Distinct from finding nothing: persistence could not be judged.
        assert detect_sustained([140.0, 140.0], a_baseline()) is None


class TestExplainIsSharedWithTheBackfill:
    """One definition of the sentence, so two writers cannot drift apart.

    The detector composes it when a window is judged; `intelligence.rephrase`
    rebuilds it for rows written before the wording changed. Two copies would
    diverge the first time either was edited, and the divergence would be
    invisible — both would still read as sentences.
    """

    def test_the_detector_uses_the_shared_function(self) -> None:
        from intelligence.detection import explain

        baseline = a_baseline(median_value=8_000.0, mad=400.0, samples=54)
        outcome = detect(
            17_800.0, baseline, metric_label="Vehicles", metric="vehicle_count",
        )
        assert isinstance(outcome, Detection)

        rebuilt = explain(
            metric="vehicle_count",
            label="Vehicles",
            observed=17_800.0,
            baseline_median=8_000.0,
            samples=54,
            direction="above",
            percent_change=outcome.percent_change,
        )
        assert outcome.explanation == rebuilt
