"""CPCB's AQI calculation, against values from the published breakpoint table.

These are not chosen examples — each is a boundary or a midpoint that the CPCB
table fixes exactly, so a drift in the constants shows up as a failing number
rather than as air quality that is quietly wrong on every screen.
"""

import pytest

from ingest.cpcb_aqi import category_for, compute, sub_index


class TestSubIndex:
    @pytest.mark.parametrize("pollutant,concentration,expected", [
        # Band boundaries, where the published table gives the index outright.
        ("PM2.5", 0, 0),
        ("PM2.5", 30, 50),
        ("PM2.5", 60, 100),
        ("PM2.5", 90, 200),
        ("PM10", 100, 100),
        ("PM10", 250, 200),
        ("NO2", 40, 50),
        ("CO", 2, 100),
    ])
    def test_boundaries_match_the_published_table(self, pollutant, concentration, expected):
        assert sub_index(pollutant, concentration) == expected

    def test_interpolates_linearly_inside_a_band(self):
        # Halfway between 30 and 60 µg/m³ of PM2.5 is halfway between 51 and 100.
        assert sub_index("PM2.5", 45) == 76

    def test_off_scale_stops_at_500(self):
        # The index ends at 500. Extrapolating to 640 would imply a precision
        # the scale does not define.
        assert sub_index("PM2.5", 900) == 500

    def test_unknown_pollutant_has_no_sub_index(self):
        # None, not zero. Zero is "clean", and a pollutant CPCB has no
        # breakpoints for has not been assessed at all.
        assert sub_index("BENZENE", 12) is None


class TestCompute:
    def test_takes_the_maximum_not_the_mean(self):
        # One pollutant at a harmful level makes the air harmful. Averaging
        # would let a dangerous reading disappear behind clean ones.
        result = compute({"PM2.5": 250, "PM10": 20, "NO2": 10})
        assert result is not None
        assert result.aqi == 400
        assert result.dominant == "PM2.5"

    def test_names_the_pollutant_responsible(self):
        # An AQI without its dominant pollutant tells a reader nothing about
        # what to do.
        result = compute({"PM10": 300, "PM2.5": 20, "NO2": 30})
        assert result is not None
        assert result.dominant == "PM10"

    def test_refuses_below_three_pollutants(self):
        # CPCB's own minimum. Two gases do not make an index.
        assert compute({"NO2": 50, "SO2": 30}) is None

    def test_refuses_without_a_particulate(self):
        # CPCB requires PM2.5 or PM10 to be among them.
        assert compute({"NO2": 50, "SO2": 30, "CO": 1.5, "NH3": 100}) is None

    def test_publishes_when_the_rules_are_met(self):
        result = compute({"PM2.5": 45, "NO2": 40, "CO": 1})
        assert result is not None
        assert result.category == "SATISFACTORY"
        assert set(result.sub_indices) == {"PM2.5", "NO2", "CO"}


class TestCategory:
    @pytest.mark.parametrize("aqi,expected", [
        (0, "GOOD"), (50, "GOOD"), (51, "SATISFACTORY"), (100, "SATISFACTORY"),
        (101, "MODERATE"), (200, "MODERATE"), (201, "POOR"), (300, "POOR"),
        (301, "VERY_POOR"), (400, "VERY_POOR"), (401, "SEVERE"), (500, "SEVERE"),
    ])
    def test_bands_match_the_bulletins(self, aqi, expected):
        assert category_for(aqi) == expected
