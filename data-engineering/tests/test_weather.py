"""Reading Open-Meteo's weather, and refusing what cannot be stated.

The HTTP call is stubbed. What is under test is the mapping between WMO's code
list and this platform's nine conditions — the place where a wrong answer is
invisible, because a mislabelled sky is still a well-formed row.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

from ingest.weather import WMO_CONDITION, City, read

NOW = datetime(2026, 8, 9, 21, 0, tzinfo=timezone.utc)
DELHI = City(1, "delhi", 28.61, 77.21)

CONDITIONS = {
    "CLEAR", "CLOUDY", "OVERCAST", "LIGHT_RAIN", "RAIN", "HEAVY_RAIN",
    "THUNDERSTORM", "FOG", "HAZE",
}


def block(**current) -> dict:
    base = {
        "time": NOW.strftime("%Y-%m-%dT%H:%M"),
        "temperature_2m": 27.6,
        "relative_humidity_2m": 91,
        "precipitation": 0.1,
        "wind_speed_10m": 2.0,
        "visibility": 5000.0,
        "weather_code": 51,
    }
    base.update(current)
    return {"current": base}


class TestTheConditionScale:
    def test_every_mapped_code_lands_on_a_condition_the_schema_allows(self) -> None:
        # ck_weather_events_condition rejects anything else, so a mapping typo
        # would fail the insert rather than the test — after the fetch, in the
        # workflow, at the far end of the run.
        assert set(WMO_CONDITION.values()) <= CONDITIONS

    def test_snow_has_no_target_and_is_not_given_one(self) -> None:
        # 71-77 and 85-86 are snow. This scale was built for Indian metros and
        # has no word for it; filing it under OVERCAST would put a wrong sky on
        # a dashboard, which is worse than losing the row.
        for code in (71, 73, 75, 77, 85, 86):
            assert code not in WMO_CONDITION
            assert read(DELHI, block(weather_code=code), NOW) is None

    def test_haze_is_never_produced_here(self) -> None:
        # The platform has HAZE and WMO has no equivalent. It stays something
        # only the generator emits, which is the truth about it.
        assert "HAZE" not in WMO_CONDITION.values()

    def test_drizzle_is_light_rain_and_a_downpour_is_not(self) -> None:
        assert read(DELHI, block(weather_code=51), NOW).condition == "LIGHT_RAIN"
        assert read(DELHI, block(weather_code=65), NOW).condition == "HEAVY_RAIN"
        assert read(DELHI, block(weather_code=95), NOW).condition == "THUNDERSTORM"
        assert read(DELHI, block(weather_code=0), NOW).condition == "CLEAR"


class TestRefusals:
    def test_a_stale_reading_is_refused(self) -> None:
        old = NOW - timedelta(hours=5)
        assert read(DELHI, block(time=old.strftime("%Y-%m-%dT%H:%M")), NOW) is None

    def test_a_partial_reading_is_refused_rather_than_completed(self) -> None:
        # Four of these columns are NOT NULL. Inventing one to satisfy the
        # constraint would be the platform filling its own gap.
        for missing in ("temperature_2m", "relative_humidity_2m",
                        "precipitation", "wind_speed_10m"):
            assert read(DELHI, block(**{missing: None}), NOW) is None

    def test_missing_visibility_is_allowed_because_the_column_is_not(self) -> None:
        reading = read(DELHI, block(visibility=None), NOW)
        assert reading is not None and reading.visibility_km is None


class TestUnits:
    def test_precipitation_becomes_a_rate(self) -> None:
        # The API reports millimetres fallen in a quarter-hour interval; the
        # column is millimetres per hour. Left unconverted, every rain figure
        # would read a quarter of what fell.
        assert read(DELHI, block(precipitation=0.5), NOW).precipitation_mm_h == 2.0

    def test_visibility_becomes_kilometres(self) -> None:
        assert read(DELHI, block(visibility=5000.0), NOW).visibility_km == 5.0

    def test_humidity_is_held_inside_the_range_the_column_allows(self) -> None:
        assert read(DELHI, block(relative_humidity_2m=104), NOW).humidity_pct == 100.0
