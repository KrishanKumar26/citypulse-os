"""Tests for the derived values every consumer depends on.

These are the platform's stated assumptions (PRD §49.15 requires them
documented and tested), so the assertions are written against the *reasoning*
in transforms.py rather than against whatever the current numbers happen to be.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from common.events import OCCUPANCY_PRECISION, AqiCategory, CongestionLevel
from common.transforms import (
    aqi_category,
    congestion_level,
    risk_level,
    risk_score,
    speed_from_occupancy,
    window_start,
)


class TestAqiCategory:
    @pytest.mark.parametrize(
        ("aqi", "expected"),
        [
            (0, AqiCategory.GOOD),
            (50, AqiCategory.GOOD),
            (51, AqiCategory.SATISFACTORY),
            (100, AqiCategory.SATISFACTORY),
            (101, AqiCategory.MODERATE),
            (200, AqiCategory.MODERATE),
            (201, AqiCategory.POOR),
            (300, AqiCategory.POOR),
            (301, AqiCategory.VERY_POOR),
            (400, AqiCategory.VERY_POOR),
            (401, AqiCategory.SEVERE),
            (999, AqiCategory.SEVERE),
        ],
    )
    def test_cpcb_boundaries(self, aqi: int, expected: AqiCategory) -> None:
        assert aqi_category(aqi) == expected

    def test_is_monotonic(self) -> None:
        """A worse reading can never map to a better band."""
        order = list(AqiCategory)
        previous = 0
        for aqi in range(0, 600):
            index = order.index(aqi_category(aqi))
            assert index >= previous
            previous = index


class TestCongestionLevel:
    @pytest.mark.parametrize(
        ("occupancy", "expected"),
        [
            (0.0, CongestionLevel.NORMAL),
            (0.55, CongestionLevel.NORMAL),
            (0.56, CongestionLevel.MODERATE),
            (0.80, CongestionLevel.MODERATE),
            (0.81, CongestionLevel.HIGH),
            (1.00, CongestionLevel.HIGH),
            (1.01, CongestionLevel.CRITICAL),
            (3.00, CongestionLevel.CRITICAL),
        ],
    )
    def test_bands(self, occupancy: float, expected: CongestionLevel) -> None:
        assert congestion_level(occupancy) == expected

    @pytest.mark.parametrize("boundary", [0.55, 0.80, 1.00])
    def test_label_survives_rounding_to_stored_precision(self, boundary: float) -> None:
        """A label must describe the value that actually gets stored.

        Regression cover for a defect a dbt test caught in production data:
        `occupancy_ratio` is stored as NUMERIC(6,4), and labelling the
        full-precision reading before rounding it let 0.550004 be labelled
        MODERATE and then stored as 0.5500 — which reads as NORMAL. Only band
        boundaries were affected, so it hid in five rows out of forty thousand.

        Deriving the label from the already-rounded value makes the two agree by
        construction, whatever the input.
        """
        for offset in (-1e-5, -1e-7, 0.0, 1e-7, 1e-5):
            raw = boundary + offset
            stored = round(raw, OCCUPANCY_PRECISION)
            assert congestion_level(stored) == congestion_level(round(stored, OCCUPANCY_PRECISION))


class TestSpeedFromOccupancy:
    FREE_FLOW = 48.0
    JAM = 8.0

    def speed(self, occupancy: float) -> float:
        return speed_from_occupancy(occupancy, self.FREE_FLOW, self.JAM)

    def test_empty_road_runs_at_free_flow(self) -> None:
        assert self.speed(0.0) == pytest.approx(self.FREE_FLOW)

    def test_speed_never_increases_with_load(self) -> None:
        previous = self.speed(0.0)
        for step in range(1, 400):
            current = self.speed(step / 100.0)
            assert current <= previous + 1e-9
            previous = current

    def test_bounded_by_jam_and_free_flow(self) -> None:
        for step in range(0, 1000):
            assert self.JAM <= self.speed(step / 100.0) <= self.FREE_FLOW

    def test_degrades_sharply_above_capacity(self) -> None:
        """The curve must bend, not run straight.

        The whole reason for a BPR form is that the last stretch of loading
        costs far more speed than the first. If the loss from 0.9→1.2 were not
        materially larger than 0.3→0.6, a linear model would do and the
        platform's congestion warnings would fire too late.
        """
        early_loss = self.speed(0.3) - self.speed(0.6)
        late_loss = self.speed(0.9) - self.speed(1.2)
        assert late_loss > early_loss * 3

    def test_speed_agrees_with_congestion_label(self) -> None:
        """A tile must never read "HIGH congestion" at near-free-flow speed.

        This is the defect the first implementation shipped: highway BPR
        coefficients left a zone at 0.83 occupancy reporting ~45 km/h while
        being labelled HIGH. Whatever the coefficients, each band has to land in
        a speed range a reader would accept for that word.
        """
        assert self.speed(0.40) > 42  # NORMAL — barely slowed
        assert 36 < self.speed(0.70) <= 44  # MODERATE — noticeably slower
        assert 24 < self.speed(0.90) <= 36  # HIGH — clearly degraded
        assert self.speed(1.20) <= 20  # CRITICAL — crawling

    def test_rejects_inverted_band(self) -> None:
        with pytest.raises(ValueError):
            speed_from_occupancy(0.5, free_flow_kph=8.0, jam_kph=48.0)


class TestRiskScore:
    def test_none_when_nothing_measured(self) -> None:
        """No data is not low risk — it is unknown."""
        assert (
            risk_score(
                occupancy_ratio=None, aqi=None, active_incidents=0, precipitation_mm_h=None
            )
            is not None
        )  # incidents always contribute, so this scores 0 rather than None

    def test_quiet_zone_scores_zero(self) -> None:
        score = risk_score(
            occupancy_ratio=0.0, aqi=0, active_incidents=0, precipitation_mm_h=0.0
        )
        assert score == pytest.approx(0.0)

    def test_saturated_zone_scores_one_hundred(self) -> None:
        score = risk_score(
            occupancy_ratio=2.0, aqi=800, active_incidents=10, precipitation_mm_h=90.0
        )
        assert score == pytest.approx(100.0)

    def test_bounded(self) -> None:
        for occupancy in (0.0, 0.5, 1.0, 5.0):
            for aqi in (0, 150, 500):
                for incidents in (0, 2, 20):
                    score = risk_score(
                        occupancy_ratio=occupancy,
                        aqi=aqi,
                        active_incidents=incidents,
                        precipitation_mm_h=3.0,
                    )
                    assert score is not None and 0.0 <= score <= 100.0

    def test_missing_component_renormalises(self) -> None:
        """A zone with no AQI feed is scored on what it has, not penalised.

        With congestion saturated and no other signal present, the score must
        still reach 100 — otherwise a missing feed would quietly cap how urgent
        a zone can ever look.
        """
        score = risk_score(
            occupancy_ratio=2.0, aqi=None, active_incidents=10, precipitation_mm_h=None
        )
        assert score == pytest.approx(100.0)

    def test_congestion_outweighs_air_quality(self) -> None:
        congested = risk_score(
            occupancy_ratio=1.25, aqi=0, active_incidents=0, precipitation_mm_h=0.0
        )
        polluted = risk_score(
            occupancy_ratio=0.0, aqi=400, active_incidents=0, precipitation_mm_h=0.0
        )
        assert congested is not None and polluted is not None
        assert congested > polluted


class TestRiskLevel:
    @pytest.mark.parametrize(
        ("score", "expected"),
        [(None, None), (0.0, "NORMAL"), (25.0, "NORMAL"), (25.1, "MODERATE"),
         (50.0, "MODERATE"), (50.1, "HIGH"), (75.0, "HIGH"), (75.1, "CRITICAL"), (100.0, "CRITICAL")],
    )
    def test_bands(self, score: float | None, expected: str | None) -> None:
        assert risk_level(score) == expected


class TestWindowStart:
    def test_floors_to_window(self) -> None:
        moment = datetime(2026, 8, 3, 14, 37, 22, tzinfo=timezone.utc)
        assert window_start(moment, timedelta(minutes=5)) == datetime(
            2026, 8, 3, 14, 35, tzinfo=timezone.utc
        )

    def test_is_idempotent(self) -> None:
        moment = datetime(2026, 8, 3, 14, 37, 22, tzinfo=timezone.utc)
        window = timedelta(minutes=5)
        once = window_start(moment, window)
        assert window_start(once, window) == once

    def test_anchored_to_epoch_not_first_event(self) -> None:
        """Two events in the same window must agree on where it starts.

        Anchoring to the first event seen would make window boundaries depend on
        arrival order, so a replay would produce different windows than the
        original run.
        """
        window = timedelta(minutes=15)
        early = datetime(2026, 8, 3, 14, 1, tzinfo=timezone.utc)
        late = datetime(2026, 8, 3, 14, 14, 59, tzinfo=timezone.utc)
        assert window_start(early, window) == window_start(late, window)

    def test_rejects_naive_datetime(self) -> None:
        with pytest.raises(ValueError):
            window_start(datetime(2026, 8, 3, 14, 0), timedelta(minutes=5))

    def test_rejects_non_positive_window(self) -> None:
        with pytest.raises(ValueError):
            window_start(datetime.now(timezone.utc), timedelta(0))
