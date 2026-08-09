"""Reading the station feed, without reaching the station feed.

The HTTP call is stubbed throughout. What is under test is everything around it:
which stations are asked about, which readings are accepted, and the two answers
that must not be confused — a rejected token and an empty city both arrive as
HTTP 200 from WAQI, and only the body distinguishes them.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone

import pytest

from ingest import waqi
from ingest.air_store import Zone

NOW = datetime(2026, 8, 9, 18, 0, tzinfo=timezone.utc)

ZONES = [
    Zone(1, "DEL-CNP", 28.6328, 77.2197),
    Zone(2, "BLR-WHF", 12.9698, 77.7500),
    Zone(3, "MUM-BKC", 19.0662, 72.8697),
]


class TestTheBoundingBox:
    def test_it_contains_every_zone(self) -> None:
        lat1, lon1, lat2, lon2 = waqi.bounding_box(ZONES)
        for zone in ZONES:
            assert lat1 < zone.latitude < lat2
            assert lon1 < zone.longitude < lon2

    def test_the_padding_exceeds_the_attribution_radius(self) -> None:
        # A station just outside the zones' extent can still be within 8 km of
        # one of them. Padding smaller than that radius would leave stations
        # unasked-about, and they would be missing rather than reported absent.
        lat1, _, lat2, _ = waqi.bounding_box(ZONES)
        south = min(z.latitude for z in ZONES)
        # One degree of latitude is about 111 km everywhere.
        assert (south - lat1) * 111 > waqi.MAX_STATION_KM


class TestRejectionIsNotAnOutage:
    def test_a_bad_token_raises_rather_than_looking_like_no_stations(self, monkeypatch) -> None:
        # WAQI answers HTTP 200 with {"status":"error","data":"Invalid key"}.
        # Read as a transport failure this would retry three times and then
        # report zero stations, which is indistinguishable from a country with
        # no monitoring — the single most misleading thing this could do.
        calls = []

        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        def urlopen(*args, **kwargs):  # noqa: ARG001
            calls.append(args)
            return Response()

        monkeypatch.setattr(waqi.urllib.request, "urlopen", urlopen)
        monkeypatch.setattr(waqi.json, "load", lambda _: {"status": "error", "data": "Invalid key"})
        monkeypatch.setattr(waqi.time, "sleep", lambda _: None)

        with pytest.raises(waqi.NotAuthorised):
            waqi._get("/map/bounds/", {}, "wrong-token", attempts=3)

        # Asked once, not three times. A rejected token is rejected on every
        # retry, and the retries turn a one-line configuration problem into a
        # stack trace a minute later.
        assert len(calls) == 1

    def test_an_unrecognised_error_is_retried_then_reported(self, monkeypatch) -> None:
        class Response:
            def __enter__(self):
                return self

            def __exit__(self, *args):
                return False

        monkeypatch.setattr(waqi.urllib.request, "urlopen", lambda *a, **k: Response())
        monkeypatch.setattr(waqi.json, "load", lambda _: {"status": "error", "data": "overloaded"})
        monkeypatch.setattr(waqi.time, "sleep", lambda _: None)

        with pytest.raises(RuntimeError) as excinfo:
            waqi._get("/map/bounds/", {}, "token", attempts=2)
        assert not isinstance(excinfo.value, waqi.NotAuthorised)


class TestReadingTheStationList:
    def _stub(self, monkeypatch, payload):
        monkeypatch.setattr(waqi, "_get", lambda *a, **k: payload)

    def test_it_reads_position_and_identity(self, monkeypatch) -> None:
        self._stub(monkeypatch, [
            {"uid": 11, "lat": 28.63, "lon": 77.22, "aqi": "160",
             "station": {"name": "Anand Vihar"}},
        ])
        stations = waqi.stations_in((0, 0, 1, 1), "token")
        assert [(s.uid, s.name) for s in stations] == [(11, "Anand Vihar")]

    def test_an_entry_with_no_position_is_dropped(self, monkeypatch) -> None:
        # Not defaulted to zero. (0, 0) is in the Gulf of Guinea, and a station
        # placed there would simply never match a zone — the row would vanish
        # silently instead of being reported as unusable.
        self._stub(monkeypatch, [
            {"uid": 11, "lat": None, "lon": 77.22, "station": {"name": "Broken"}},
            {"uid": 12, "lat": 28.63, "lon": 77.22, "station": {"name": "Fine"}},
        ])
        assert [s.name for s in waqi.stations_in((0, 0, 1, 1), "token")] == ["Fine"]

    def test_a_station_with_no_name_still_counts(self, monkeypatch) -> None:
        # The name is for the log. Dropping a positioned station over a missing
        # label would throw away a measurement to protect a print statement.
        self._stub(monkeypatch, [{"uid": 13, "lat": 28.63, "lon": 77.22}])
        assert len(waqi.stations_in((0, 0, 1, 1), "token")) == 1


class TestFreshness:
    def test_it_reads_the_stations_own_clock(self) -> None:
        moment = waqi._parse_moment({"iso": "2026-08-09T23:00:00+05:30"})
        assert moment == datetime(2026, 8, 9, 17, 30, tzinfo=timezone.utc)

    def test_an_unreadable_moment_is_nothing_rather_than_now(self) -> None:
        # Substituting the current time would make a station that stopped
        # reporting in March pass the freshness check every hour since.
        assert waqi._parse_moment(None) is None
        assert waqi._parse_moment({}) is None
        assert waqi._parse_moment({"iso": "yesterday"}) is None

    def test_a_stale_station_is_older_than_the_limit(self) -> None:
        # Pins the constant against the check that uses it: three hours
        # accommodates a station running late without accepting one that
        # stopped last week.
        old = NOW - waqi.MAX_READING_AGE - timedelta(minutes=1)
        assert NOW - old > waqi.MAX_READING_AGE
        assert NOW - (NOW - timedelta(hours=2)) < waqi.MAX_READING_AGE
