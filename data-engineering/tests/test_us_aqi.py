"""The US-EPA sub-index inverse, which decides what every station reading says.

WAQI publishes an index on the US scale; this platform reports CPCB's. The two
disagree by a whole category over the range Indian cities actually sit in — a US
192 is *Unhealthy* while CPCB calls 192 *MODERATE* — so the number is taken back
to the concentration behind it and CPCB's index is computed from that.

These tests exist because that inversion is the only place a station reading can
go quietly wrong. A mistake here does not raise; it publishes a plausible AQI
against the wrong table and labels it measured.
"""

from __future__ import annotations

import math

import pytest

from ingest.cpcb_aqi import compute
from ingest.us_aqi import concentration_for, concentrations


class TestTheInverse:
    def test_a_us_index_of_42_is_ten_micrograms_of_pm25(self) -> None:
        # 42 sits in the first band, 0-12.0 µg/m³ against 0-50, so the answer is
        # 42/50 of 12. Worked by hand rather than from the implementation: a
        # test that recomputes the code it checks agrees with any bug in it.
        assert concentration_for("PM2.5", 42) == pytest.approx(10.0, abs=0.2)

    def test_the_top_of_a_band_is_the_top_of_its_concentration_range(self) -> None:
        assert concentration_for("PM2.5", 100) == pytest.approx(35.4, abs=0.5)
        assert concentration_for("PM10", 50) == pytest.approx(54, abs=1.0)

    def test_it_uses_the_2016_table_and_not_the_2024_revision(self) -> None:
        # aqicn.org/scale/ states US-EPA 2016, where 50 is 12.0 µg/m³ of PM2.5.
        # The 2024 revision moved that to 9.0. Inverting against the wrong one
        # would shift every particulate reading by a third at the clean end.
        assert concentration_for("PM2.5", 50) == pytest.approx(12.0, abs=0.3)

    def test_it_is_monotonic(self) -> None:
        # A higher index must never mean cleaner air. Checked across the band
        # joins, which is where a transcription error in the table shows up.
        values = [concentration_for("PM2.5", i) for i in range(0, 500, 7)]
        assert all(a is not None and b is not None and a < b
                   for a, b in zip(values, values[1:]))

    def test_gases_arrive_in_cpcb_units(self) -> None:
        # EPA states NO2 in ppb and CPCB in µg/m³. At EPA's own reference
        # conditions a mole fills 24.45 L, so the factor is 46.0055/24.45.
        ppb = 53 * (50 + 0.5) / 51  # what the inverse resolves index 50 to
        assert concentration_for("NO2", 50) == pytest.approx(ppb * 46.0055 / 24.45, rel=1e-3)

    def test_carbon_monoxide_arrives_in_milligrams(self) -> None:
        # CPCB is the one pollutant stated in mg/m³, and its breakpoints start
        # at 1. A value returned in µg/m³ would be a thousand times the scale
        # and would peg every station at SEVERE.
        value = concentration_for("CO", 50)
        assert value is not None
        assert 0.5 < value < 10

    def test_above_the_scale_is_nothing_rather_than_an_extrapolation(self) -> None:
        # Ozone's US table stops at 300. Beyond it EPA's linear form is not
        # defined, and continuing the line would manufacture a concentration
        # from a number that only says "off the scale".
        assert concentration_for("OZONE", 400) is None

    def test_an_unknown_pollutant_has_no_inverse(self) -> None:
        assert concentration_for("NH3", 50) is None  # US AQI does not index it
        assert concentration_for("nonsense", 50) is None


class TestReadingAnIaqiBlock:
    def test_weather_entries_are_not_treated_as_pollutants(self) -> None:
        # WAQI puts temperature, humidity, wind and pressure in the same block.
        # Mapping one would put a wind speed through a pollutant's breakpoints.
        block = {"pm25": {"v": 42}, "t": {"v": 27.6}, "h": {"v": 94},
                 "w": {"v": 9.7}, "p": {"v": 996}}
        assert set(concentrations(block)) == {"PM2.5"}

    def test_a_missing_pollutant_is_absent_rather_than_zero(self) -> None:
        # Zero would read as clean air, and would satisfy CPCB's
        # minimum-pollutant rule on the strength of a reading that does not
        # exist. Absent lets compute() see the gap and refuse.
        block = {"pm25": {"v": 42}, "no2": {}, "so2": {"v": None}, "co": "bad"}
        assert set(concentrations(block)) == {"PM2.5"}
        assert compute(concentrations(block)) is None

    def test_a_full_station_reaches_a_cpcb_index(self) -> None:
        block = {"pm25": {"v": 160}, "pm10": {"v": 90}, "no2": {"v": 30},
                 "o3": {"v": 25}, "so2": {"v": 10}}
        result = compute(concentrations(block))
        assert result is not None
        # US 160 for PM2.5 is about 73 µg/m³, which CPCB indexes near 145 —
        # MODERATE, not the *Unhealthy* the US number reads as. That gap is the
        # entire reason this module exists.
        assert result.dominant == "PM2.5"
        assert 130 <= result.aqi <= 160
        assert result.category == "MODERATE"

    def test_the_result_is_a_number_not_a_nan(self) -> None:
        values = concentrations({"pm25": {"v": 42}, "pm10": {"v": 30}, "o3": {"v": 20}})
        assert all(math.isfinite(v) for v in values.values())
