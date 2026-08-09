"""Tests for windowed aggregation into curated zone metrics."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from pipeline.aggregate import aggregate


BASE = datetime(2026, 8, 3, 12, 0, tzinfo=timezone.utc)
WINDOW = timedelta(minutes=5)

ZONE_IDS = {"BLR-WHF": 1, "BLR-KOR": 2}
ZONE_CITY = {"BLR-WHF": "bengaluru", "BLR-KOR": "bengaluru"}


def traffic(minute: int, *, zone: str = "BLR-WHF", occupancy: float = 0.5,
            speed: float = 40.0, vehicles: int = 100) -> dict:
    return {
        "event_type": "TRAFFIC",
        "zone_code": zone,
        "event_time": BASE + timedelta(minutes=minute),
        "occupancy_ratio": occupancy,
        "average_speed_kph": speed,
        "vehicle_count": vehicles,
    }


def air(minute: int, *, zone: str = "BLR-WHF", aqi: int = 120) -> dict:
    return {
        "event_type": "AIR_QUALITY",
        "zone_code": zone,
        "event_time": BASE + timedelta(minutes=minute),
        "aqi": aqi,
    }


def weather(minute: int, *, condition: str = "CLEAR", rain: float = 0.0,
            temp: float = 28.0) -> dict:
    return {
        "event_type": "WEATHER",
        "city_slug": "bengaluru",
        "event_time": BASE + timedelta(minutes=minute),
        "condition": condition,
        "precipitation_mm_h": rain,
        "temperature_c": temp,
    }


def incident(external_id: str, *, start_min: int, resolved_min: int | None,
             zone: str = "BLR-WHF", status: str = "REPORTED") -> dict:
    return {
        "event_type": "INCIDENT",
        "zone_code": zone,
        "external_id": external_id,
        "event_time": BASE + timedelta(minutes=start_min),
        "started_at": BASE + timedelta(minutes=start_min),
        "resolved_at": None if resolved_min is None else BASE + timedelta(minutes=resolved_min),
        "status": status,
    }


def run(events: list[dict]) -> list[dict]:
    return aggregate(events, zone_ids=ZONE_IDS, zone_city=ZONE_CITY, window=WINDOW)


class TestWindowing:
    def test_events_in_one_window_produce_one_row(self) -> None:
        rows = run([traffic(0), traffic(1), traffic(4)])
        assert len(rows) == 1
        assert rows[0]["sample_count"] == 3

    def test_events_split_across_windows(self) -> None:
        rows = run([traffic(0), traffic(6)])
        assert len(rows) == 2
        assert rows[0]["window_start"] != rows[1]["window_start"]

    def test_zones_are_kept_separate(self) -> None:
        rows = run([traffic(0, zone="BLR-WHF"), traffic(0, zone="BLR-KOR")])
        assert {r["zone_code"] for r in rows} == {"BLR-WHF", "BLR-KOR"}

    def test_unknown_zone_is_dropped_not_crashed(self) -> None:
        rows = run([traffic(0, zone="XX-GHOST")])
        assert rows == []


class TestAggregation:
    def test_occupancy_and_speed_are_averaged(self) -> None:
        rows = run([traffic(0, occupancy=0.4, speed=44.0), traffic(1, occupancy=0.6, speed=36.0)])
        assert rows[0]["occupancy_ratio"] == pytest.approx(0.5)
        assert rows[0]["average_speed_kph"] == pytest.approx(40.0)

    def test_vehicle_counts_are_summed_not_averaged(self) -> None:
        """Each reading counts vehicles in its interval, so the window is their sum."""
        rows = run([traffic(0, vehicles=100), traffic(1, vehicles=150)])
        assert rows[0]["vehicle_count"] == 250

    def test_missing_signal_stays_null_rather_than_zero(self) -> None:
        """"No AQI reading" and "AQI is 0" are different facts."""
        rows = run([traffic(0)])
        assert rows[0]["aqi"] is None
        assert rows[0]["aqi_category"] is None

    def test_worst_weather_in_window_wins(self) -> None:
        """A window containing a thunderstorm must not report CLEAR.

        Taking the last reading would let a storm that passed mid-window vanish
        from the record entirely.
        """
        rows = run([traffic(0), weather(0, condition="THUNDERSTORM"), weather(4, condition="CLEAR")])
        assert rows[0]["weather_condition"] == "THUNDERSTORM"

    def test_city_weather_reaches_every_zone_in_that_city(self) -> None:
        rows = run([
            traffic(0, zone="BLR-WHF"), traffic(0, zone="BLR-KOR"),
            weather(0, temp=31.0, rain=4.0),
        ])
        assert len(rows) == 2
        assert all(r["temperature_c"] == pytest.approx(31.0) for r in rows)
        assert all(r["precipitation_mm_h"] == pytest.approx(4.0) for r in rows)

    def test_derived_labels_match_their_numbers(self) -> None:
        rows = run([traffic(0, occupancy=1.4), air(0, aqi=350)])
        assert rows[0]["congestion_level"] == "CRITICAL"
        assert rows[0]["aqi_category"] == "VERY_POOR"


class TestIncidentReconciliation:
    """Regression cover for a defect that inflated every risk score.

    An incident is reported twice: REPORTED with `resolved_at = null`, then
    CLEARED with a time. Counted as two independent incidents, the REPORTED
    record reads as "still open" and is counted as active in every later
    window — a week of data produced tens of thousands of simultaneous
    incidents per zone.
    """

    def test_reported_then_cleared_is_one_incident(self) -> None:
        events = [
            traffic(0),
            incident("INC-1", start_min=0, resolved_min=None, status="REPORTED"),
            incident("INC-1", start_min=0, resolved_min=2, status="CLEARED"),
        ]
        rows = run(events)
        assert rows[0]["active_incidents"] == 1

    def test_cleared_incident_is_not_active_in_later_windows(self) -> None:
        events = [
            traffic(0), traffic(10), traffic(20),
            incident("INC-1", start_min=0, resolved_min=None, status="REPORTED"),
            incident("INC-1", start_min=0, resolved_min=3, status="CLEARED"),
        ]
        rows = sorted(run(events), key=lambda r: r["window_start"])
        assert rows[0]["active_incidents"] == 1  # window containing 00:00–00:05
        assert rows[1]["active_incidents"] == 0  # resolved before this window
        assert rows[2]["active_incidents"] == 0

    def test_still_open_incident_stays_active(self) -> None:
        events = [
            traffic(0), traffic(10),
            incident("INC-1", start_min=0, resolved_min=None, status="REPORTED"),
        ]
        rows = sorted(run(events), key=lambda r: r["window_start"])
        assert all(r["active_incidents"] == 1 for r in rows)

    def test_resolution_is_final_regardless_of_event_order(self) -> None:
        """A late-arriving REPORTED must not reopen a closed incident."""
        ordered = [traffic(0),
                   incident("INC-1", start_min=0, resolved_min=None),
                   incident("INC-1", start_min=0, resolved_min=2)]
        reversed_order = [traffic(0),
                          incident("INC-1", start_min=0, resolved_min=2),
                          incident("INC-1", start_min=0, resolved_min=None)]
        assert run(ordered)[0]["active_incidents"] == run(reversed_order)[0]["active_incidents"]

    def test_distinct_incidents_both_counted(self) -> None:
        events = [
            traffic(0),
            incident("INC-1", start_min=0, resolved_min=None),
            incident("INC-2", start_min=1, resolved_min=None),
        ]
        assert run(events)[0]["active_incidents"] == 2

    def test_incident_in_another_zone_is_not_counted(self) -> None:
        events = [
            traffic(0, zone="BLR-WHF"),
            incident("INC-1", start_min=0, resolved_min=None, zone="BLR-KOR"),
        ]
        rows = [r for r in run(events) if r["zone_code"] == "BLR-WHF"]
        assert rows[0]["active_incidents"] == 0


class TestRiskIsDerived:
    def test_risk_rises_with_congestion(self) -> None:
        calm = run([traffic(0, occupancy=0.2)])[0]["risk_score"]
        busy = run([traffic(0, occupancy=1.2)])[0]["risk_score"]
        assert calm is not None and busy is not None and busy > calm

    def test_risk_level_matches_score(self) -> None:
        row = run([traffic(0, occupancy=1.5), air(0, aqi=400)])[0]
        assert row["risk_score"] is not None
        assert row["risk_level"] in {"NORMAL", "MODERATE", "HIGH", "CRITICAL"}

    def test_demo_flag_is_carried_through(self) -> None:
        """PRD §42: synthetic data stays labelled all the way to curated."""
        assert run([traffic(0)])[0]["demo_data"] is True


# ---------------------------------------------------------------------------


def test_stats_report_what_the_stage_dropped():
    """The transform stage drops events silently; something has to count them.

    A window simply never appears for an event whose timestamp will not parse or
    whose zone the catalogue does not know. Nothing else in the pipeline can see
    that happen, so a mis-seeded zone could remove a junction from the dashboard
    with no trace anywhere — which is the kind of absence this product is built
    to make visible.
    """
    stats: dict = {}
    unparseable = traffic(0)
    unparseable["event_time"] = "not-a-timestamp"
    events = [traffic(0), unparseable, traffic(0, zone="XXX-UNKNOWN")]
    rows = aggregate(events, zone_ids=ZONE_IDS, zone_city=ZONE_CITY, window=WINDOW, stats=stats)

    assert stats["events_seen"] == 3
    assert stats["dropped_no_timestamp"] == 1
    assert stats["dropped_unknown_zone"] == 1
    assert stats["windows_emitted"] == len(rows)


def test_stats_are_optional():
    # The Spark job and most tests do not pass stats; collecting them must not
    # be a condition of the function working.
    rows = aggregate([traffic(0)], zone_ids=ZONE_IDS, zone_city=ZONE_CITY, window=WINDOW)
    assert len(rows) == 1


class TestAirProvenance:
    """A measured AQI, a modelled one and a generated one are three things.

    Monitoring stations sit at fixed points and the station ingester refuses to
    attribute one beyond its distance limit, so on any real deployment most
    zones have no instrument. Copernicus CAMS covers them instead — real
    atmosphere, solved for rather than measured — and the generator covers what
    is left. These tests pin all three: each is reported as itself, the better
    one wins outright where they overlap, and none is ever averaged into another.
    """

    def test_generated_air_is_marked_generated(self) -> None:
        rows = run([traffic(0), air(0, aqi=120)])
        assert rows[0]["aqi"] == 120
        assert rows[0]["aqi_source"] == "SYNTHETIC"

    def test_measured_air_is_marked_measured(self) -> None:
        measured = air(0, aqi=200)
        measured["demo_data"] = False
        rows = run([traffic(0), measured])
        assert rows[0]["aqi"] == 200
        assert rows[0]["aqi_source"] == "MEASURED"

    def test_a_measurement_is_never_averaged_with_a_simulation(self) -> None:
        # The mean of 200 and 100 is 150, which no instrument reported and no
        # generator produced. Taking it and calling the window measured would be
        # the most convincing possible way to be wrong.
        measured = air(0, aqi=200)
        measured["demo_data"] = False
        rows = run([traffic(0), measured, air(1, aqi=100)])
        assert rows[0]["aqi"] == 200
        assert rows[0]["aqi_source"] == "MEASURED"

    def test_several_measurements_are_averaged_with_each_other(self) -> None:
        first, second = air(0, aqi=200), air(1, aqi=210)
        first["demo_data"] = second["demo_data"] = False
        rows = run([traffic(0), first, second])
        assert rows[0]["aqi"] == 205
        assert rows[0]["aqi_source"] == "MEASURED"

    def test_no_air_at_all_is_neither(self) -> None:
        # Null, not False: "nothing was measured here" and "this number was
        # invented" are different facts, and a window with no AQI has no
        # provenance to report.
        rows = run([traffic(0)])
        assert rows[0]["aqi"] is None
        assert rows[0]["aqi_source"] is None

    def test_an_uncovered_zone_keeps_generated_air(self) -> None:
        # The station covers WHF; KOR is out of range and must be unaffected.
        measured = air(0, zone="BLR-WHF", aqi=200)
        measured["demo_data"] = False
        rows = run([traffic(0, zone="BLR-WHF"), measured,
                    traffic(0, zone="BLR-KOR"), air(0, zone="BLR-KOR", aqi=90)])
        by_zone = {r["zone_code"]: r for r in rows}
        assert by_zone["BLR-WHF"]["aqi_source"] == "MEASURED"
        assert by_zone["BLR-KOR"]["aqi_source"] == "SYNTHETIC"
        assert by_zone["BLR-KOR"]["aqi"] == 90

    def test_modelled_air_is_marked_modelled(self) -> None:
        modelled = air(0, aqi=150)
        modelled["demo_data"] = False
        modelled["provenance"] = "MODELLED"
        rows = run([traffic(0), modelled])
        assert rows[0]["aqi"] == 150
        assert rows[0]["aqi_source"] == "MODELLED"

    def test_a_model_outranks_the_generator(self) -> None:
        # CAMS tracks the real atmosphere and the generator tracks nothing, so
        # where both cover a window the model is the answer — alone, not
        # averaged. 150 and 90 would mean 120, which neither produced.
        modelled = air(0, aqi=150)
        modelled["demo_data"] = False
        modelled["provenance"] = "MODELLED"
        rows = run([traffic(0), modelled, air(1, aqi=90)])
        assert rows[0]["aqi"] == 150
        assert rows[0]["aqi_source"] == "MODELLED"

    def test_an_instrument_outranks_a_model(self) -> None:
        # The distinction is kind, not freshness: something stood in this zone
        # and measured the air, and a solved field does not displace that.
        measured = air(0, aqi=200)
        measured["demo_data"] = False
        modelled = air(1, aqi=150)
        modelled["demo_data"] = False
        modelled["provenance"] = "MODELLED"
        rows = run([traffic(0), modelled, measured])
        assert rows[0]["aqi"] == 200
        assert rows[0]["aqi_source"] == "MEASURED"

    def test_the_window_stays_demo_while_traffic_is_generated(self) -> None:
        # Real air does not make the window real. Traffic is still invented, and
        # a city with measured air and synthetic traffic is exactly that.
        measured = air(0, aqi=200)
        measured["demo_data"] = False
        rows = run([traffic(0), measured])
        assert rows[0]["demo_data"] is True
        assert rows[0]["aqi_source"] == "MEASURED"
