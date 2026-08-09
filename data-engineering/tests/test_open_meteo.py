"""Averaging CAMS onto the periods CPCB's breakpoints are defined for.

The index tables are not defined on an instantaneous concentration. CPCB's
sub-indices assume a 24-hour mean for the particulates, NO2 and SO2, and an
8-hour mean for CO and ozone. Passing a single hour's value through a table
built for a daily mean reports every rush hour as a day of it, and the error is
invisible: the output is a well-formed AQI in the right range.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from ingest.open_meteo import concentrations, latest_hour

NOW = datetime(2026, 8, 9, 18, 0, tzinfo=timezone.utc)


def block(**series: list) -> dict:
    """A response covering the 48 hours ending at NOW."""
    hours = [(NOW - timedelta(hours=47 - i)).strftime("%Y-%m-%dT%H:%M")
             for i in range(48)]
    return {"hourly": {"time": hours, **series}}


def grid(payload: dict) -> list[datetime]:
    return [datetime.fromisoformat(t).replace(tzinfo=timezone.utc)
            for t in payload["hourly"]["time"]]


class TestAveragingPeriods:
    def test_particulates_average_over_twenty_four_hours(self) -> None:
        # The last 24 hours are 100; everything before is 0. A 24-hour mean is
        # 100, an hourly reading is 100, and a 48-hour mean would be 50 — so
        # this distinguishes the right window from the widest one.
        values = [0.0] * 24 + [100.0] * 24
        assert concentrations(block(pm2_5=values), 47)["PM2.5"] == 100.0

        # Shifted: the last 12 hours are 100 and the 12 before are 0, so a
        # correct 24-hour window gives 50 where an hourly read gives 100.
        values = [0.0] * 36 + [100.0] * 12
        assert concentrations(block(pm2_5=values), 47)["PM2.5"] == 50.0

    def test_ozone_and_carbon_monoxide_average_over_eight(self) -> None:
        # 8 hours at 80 preceded by zeroes: an 8-hour mean is 80, a 24-hour
        # mean would be 26.7.
        values = [0.0] * 40 + [80.0] * 8
        assert concentrations(block(ozone=values), 47)["OZONE"] == 80.0

    def test_carbon_monoxide_is_converted_to_milligrams(self) -> None:
        # Open-Meteo states all six in µg/m³; CPCB's CO breakpoints start at
        # 1 mg/m³. Left unconverted, 1686 µg/m³ of ordinary city air would land
        # a thousand-fold past the top of the scale.
        assert concentrations(block(carbon_monoxide=[1686.0] * 48), 47)["CO"] == 1.686


class TestGaps:
    def test_missing_hours_are_left_out_of_the_mean_not_zeroed(self) -> None:
        # The 24-hour window ending at index 47 holds twelve hours the model did
        # not publish and twelve at 100. The mean of what exists is 100;
        # treating the nulls as zero would report 50 and call the air twice as
        # clean as anything CAMS said.
        values = [0.0] * 24 + [None] * 12 + [100.0] * 12
        assert concentrations(block(pm2_5=values), 47)["PM2.5"] == 100.0

    def test_a_pollutant_with_nothing_in_its_window_is_absent(self) -> None:
        result = concentrations(block(pm2_5=[50.0] * 48, ozone=[None] * 48), 47)
        assert result["PM2.5"] == 50.0
        assert "OZONE" not in result

    def test_a_pollutant_that_was_never_requested_is_absent(self) -> None:
        assert set(concentrations(block(pm2_5=[50.0] * 48), 47)) == {"PM2.5"}


class TestFindingTheLatestHour:
    def test_it_ignores_the_forecast_tail(self) -> None:
        # The response runs past now into the forecast. Reading the last row
        # regardless would average a window that is mostly the future.
        payload = block(pm2_5=[50.0] * 48)
        payload["hourly"]["time"] = [
            (NOW - timedelta(hours=40 - i)).strftime("%Y-%m-%dT%H:%M") for i in range(48)
        ]
        index = latest_hour(payload, grid(payload), NOW)
        assert index == 40  # the row at exactly NOW; 41 onward are ahead of it

    def test_it_skips_hours_the_model_has_not_published(self) -> None:
        # CAMS publishes on a lag, so the newest rows are still null at the
        # moment they are asked for.
        values = [50.0] * 44 + [None] * 4
        payload = block(pm2_5=values)
        assert latest_hour(payload, grid(payload), NOW) == 43

    def test_a_response_with_nothing_in_it_has_no_latest_hour(self) -> None:
        payload = block(pm2_5=[None] * 48)
        assert latest_hour(payload, grid(payload), NOW) is None
