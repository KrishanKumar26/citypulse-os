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


class TestFindingAZonesStation:
    def _answer(self, monkeypatch, payload):
        monkeypatch.setattr(waqi, "_get", lambda *a, **k: payload)

    def test_a_station_in_the_zone_is_accepted_with_its_distance(self, monkeypatch) -> None:
        # Anand Vihar against Connaught Place: about 12 km apart in reality, so
        # this uses coordinates a kilometre away to sit inside the limit.
        self._answer(monkeypatch, {"city": {"geo": [28.6400, 77.2250]}, "iaqi": {}})
        match = waqi.nearest_station(ZONES[0], "token")
        assert match is not None
        assert match[1] < waqi.MAX_STATION_KM

    def test_a_distant_station_is_refused(self, monkeypatch) -> None:
        # /feed/geo: returns the nearest station at *any* distance. For a zone
        # with no monitoring nearby that is a station in another city, and
        # publishing it as this zone's air is the failure the limit exists for.
        self._answer(monkeypatch, {"city": {"geo": [19.0662, 72.8697]}, "iaqi": {}})
        assert waqi.nearest_station(ZONES[0], "token") is None  # Mumbai vs Delhi

    def test_a_station_with_no_coordinates_is_refused(self, monkeypatch) -> None:
        # An unknown distance cannot be checked against the limit, and accepting
        # it would let exactly the case above through unexamined.
        for payload in ({"city": {}}, {"city": {"geo": []}}, {"city": {"geo": ["x", "y"]}}, {}):
            self._answer(monkeypatch, payload)
            assert waqi.nearest_station(ZONES[0], "token") is None

    def test_the_limit_is_the_boundary_it_claims(self, monkeypatch) -> None:
        # 8 km due north of Connaught Place: one degree of latitude is ~111 km.
        just_inside = 28.6328 + (waqi.MAX_STATION_KM - 0.2) / 111
        just_outside = 28.6328 + (waqi.MAX_STATION_KM + 0.2) / 111
        self._answer(monkeypatch, {"city": {"geo": [just_inside, 77.2197]}, "iaqi": {}})
        assert waqi.nearest_station(ZONES[0], "token") is not None
        self._answer(monkeypatch, {"city": {"geo": [just_outside, 77.2197]}, "iaqi": {}})
        assert waqi.nearest_station(ZONES[0], "token") is None


class TestRejectionIsNotAnOutage:
    def test_a_bad_token_raises_rather_than_looking_like_an_unmonitored_country(self, monkeypatch) -> None:
        # WAQI answers HTTP 200 with {"status":"error","data":"Invalid key"}.
        # Read as a transport failure this would retry three times and then
        # report no station for any zone, which is indistinguishable from a
        # country with no monitoring — the single most misleading thing this could do.
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
            waqi._get("/feed/geo:28.63;77.22/", {}, "wrong-token", attempts=3)

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
            waqi._get("/feed/geo:28.63;77.22/", {}, "token", attempts=2)
        assert not isinstance(excinfo.value, waqi.NotAuthorised)


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
