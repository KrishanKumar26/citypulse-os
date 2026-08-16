"""What `ingest.tomtom` must refuse, and what it must not round off.

The rejections are the interesting half. A zone this module declines keeps its
generated traffic and says SYNTHETIC, which is true; a zone it accepts on thin
evidence puts an instrument's label on something else.
"""

from __future__ import annotations

import http.client
import json
import urllib.error
from datetime import datetime, timezone

import pytest

from ingest import tomtom
from ingest.air_store import Zone
from pipeline.provenance import MEASURED, MODELLED

NOW = datetime(2026, 8, 11, 10, 30, tzinfo=timezone.utc)

#: Connaught Place, and a segment whose shape passes within about 90 m of it.
ZONE = Zone(id=1, code="DEL-CNP", latitude=28.6328, longitude=77.2197)


def payload(**overrides) -> dict:
    segment = {
        "frc": "FRC2",
        "currentSpeed": 18,
        "freeFlowSpeed": 25,
        "confidence": 0.99,
        "roadClosure": False,
        "coordinates": {"coordinate": [
            {"latitude": 28.6336, "longitude": 77.2197},
            {"latitude": 28.6350, "longitude": 77.2210},
        ]},
    }
    segment.update(overrides)
    return {"flowSegmentData": segment}


class TestWhatIsRead:
    def test_a_good_segment_becomes_a_reading(self) -> None:
        reading = tomtom.read(ZONE, payload(), NOW)
        assert reading is not None
        assert reading.speed_kph == 18.0
        assert reading.free_flow_kph == 25.0
        assert reading.speed_ratio == 0.72
        assert reading.provenance == MEASURED

    def test_the_moment_is_the_request_not_an_invented_observation_time(self) -> None:
        # Flow Segment Data returns no observation time. The module documents
        # that it stamps the moment of asking; if that ever silently became
        # something derived, this is where it would show.
        assert tomtom.read(ZONE, payload(), NOW).event_time == NOW

    def test_a_standstill_is_a_reading_and_not_an_absence(self) -> None:
        # currentSpeed 0 is a road at a dead stop, which is the single most
        # important thing this feed can report. Truthiness tests make it
        # indistinguishable from a zone that answered nothing.
        reading = tomtom.read(ZONE, payload(currentSpeed=0), NOW)
        assert reading is not None
        assert reading.speed_ratio == 0.0


class TestProvenanceIsEarnedNotAssumed:
    def test_high_confidence_is_measured(self) -> None:
        assert tomtom.read(ZONE, payload(confidence=0.9), NOW).provenance == MEASURED

    def test_middling_confidence_is_modelled(self) -> None:
        # Real road, real hour, but TomTom leaning on its historical model.
        assert tomtom.read(ZONE, payload(confidence=0.6), NOW).provenance == MODELLED

    def test_the_boundary_is_the_one_the_module_claims(self) -> None:
        just_under = tomtom.MIN_CONFIDENCE_MEASURED - 0.01
        assert tomtom.read(ZONE, payload(confidence=just_under), NOW).provenance == MODELLED
        assert tomtom.read(
            ZONE, payload(confidence=tomtom.MIN_CONFIDENCE_MEASURED), NOW
        ).provenance == MEASURED

    def test_too_little_confidence_is_dropped_rather_than_filed_as_modelled(self) -> None:
        assert tomtom.read(ZONE, payload(confidence=0.2), NOW) is None

    def test_no_confidence_at_all_is_dropped(self) -> None:
        # Without it there is no way to tell an observation from a model, and
        # the provenance would have to be guessed.
        body = payload()
        del body["flowSegmentData"]["confidence"]
        assert tomtom.read(ZONE, body, NOW) is None


class TestItDoesNotAttributeADistantRoad:
    def test_a_segment_far_from_the_zone_is_refused(self) -> None:
        far = payload(coordinates={"coordinate": [
            {"latitude": 28.8000, "longitude": 77.4000},  # ~26 km away
        ]})
        assert tomtom.read(ZONE, far, NOW) is None

    def test_the_limit_is_the_boundary_it_claims(self) -> None:
        # One degree of latitude is about 111 km, so this steps either side of
        # MAX_SNAP_KM without depending on the haversine's exact output.
        inside = ZONE.latitude + (tomtom.MAX_SNAP_KM - 0.2) / 111
        outside = ZONE.latitude + (tomtom.MAX_SNAP_KM + 0.2) / 111
        near = payload(coordinates={"coordinate": [
            {"latitude": inside, "longitude": ZONE.longitude}]})
        away = payload(coordinates={"coordinate": [
            {"latitude": outside, "longitude": ZONE.longitude}]})
        assert tomtom.read(ZONE, near, NOW) is not None
        assert tomtom.read(ZONE, away, NOW) is None

    def test_the_nearest_point_of_the_segment_decides_not_the_first(self) -> None:
        # A long arterial may begin far away and pass through the zone. Judging
        # it by where its polyline starts would throw away a road running
        # directly past the centre.
        through = payload(coordinates={"coordinate": [
            {"latitude": ZONE.latitude + 0.5, "longitude": ZONE.longitude},  # ~55 km
            {"latitude": ZONE.latitude, "longitude": ZONE.longitude},        # on it
        ]})
        assert tomtom.read(ZONE, through, NOW) is not None

    def test_a_segment_with_no_shape_cannot_be_placed(self) -> None:
        assert tomtom.read(ZONE, payload(coordinates={"coordinate": []}), NOW) is None


class TestItDoesNotInventTheMissing:
    def test_no_free_flow_reference_means_no_ratio(self) -> None:
        assert tomtom.read(ZONE, payload(freeFlowSpeed=0), NOW) is None

    def test_a_missing_speed_is_not_a_zero(self) -> None:
        body = payload()
        del body["flowSegmentData"]["currentSpeed"]
        assert tomtom.read(ZONE, body, NOW) is None

    def test_a_response_with_no_segment_is_nothing(self) -> None:
        assert tomtom.read(ZONE, {"error": "whatever"}, NOW) is None


class TestReachingTheApi:
    def _fail_n_then(self, monkeypatch, failures: int, exc: Exception) -> list[int]:
        calls: list[int] = []

        class Response:
            status = 200
            def read(self, *_): return json.dumps(payload()).encode()
            def __enter__(self): return self
            def __exit__(self, *_): return False

        def fake_urlopen(_request, timeout=None):
            calls.append(1)
            if len(calls) <= failures:
                raise exc
            return Response()

        monkeypatch.setattr(tomtom.urllib.request, "urlopen", fake_urlopen)
        monkeypatch.setattr(tomtom.time, "sleep", lambda _s: None)
        return calls

    def test_a_truncated_body_is_retried(self, monkeypatch) -> None:
        # IncompleteRead is an HTTPException, not a URLError. It was missing
        # from the retry list once and took down a run that had already
        # fetched forty zones.
        calls = self._fail_n_then(monkeypatch, 1, http.client.IncompleteRead(b"half"))
        assert tomtom._get(ZONE, "key")["flowSegmentData"]["currentSpeed"] == 18
        assert len(calls) == 2

    def test_a_timeout_is_retried(self, monkeypatch) -> None:
        calls = self._fail_n_then(monkeypatch, 2, TimeoutError("read timed out"))
        assert tomtom._get(ZONE, "key") is not None
        assert len(calls) == 3

    def test_exhausted_retries_name_the_zone(self, monkeypatch) -> None:
        self._fail_n_then(monkeypatch, 99, TimeoutError("read timed out"))
        with pytest.raises(RuntimeError, match="DEL-CNP"):
            tomtom._get(ZONE, "key")

    def test_an_http_error_is_not_retried(self, monkeypatch) -> None:
        # A 403 for a bad key or a spent quota answers the same way every time.
        # Sixty-two zones retrying three times turns one clear failure into a
        # slow one.
        calls: list[int] = []

        def fake_urlopen(_request, timeout=None):
            calls.append(1)
            raise urllib.error.HTTPError("u", 403, "Forbidden", {}, None)

        monkeypatch.setattr(tomtom.urllib.request, "urlopen", fake_urlopen)
        with pytest.raises(urllib.error.HTTPError):
            tomtom._get(ZONE, "key")
        assert len(calls) == 1


class TestTheRequestItself:
    def test_the_unit_is_stated_rather_than_defaulted(self, monkeypatch) -> None:
        seen: dict[str, str] = {}

        class Response:
            def read(self, *_): return json.dumps(payload()).encode()
            def __enter__(self): return self
            def __exit__(self, *_): return False

        def fake_urlopen(request, timeout=None):
            seen["url"] = request.full_url
            return Response()

        monkeypatch.setattr(tomtom.urllib.request, "urlopen", fake_urlopen)
        tomtom._get(ZONE, "the-key")
        assert "unit=KMPH" in seen["url"]
        assert "28.632800%2C77.219700" in seen["url"]

    def test_the_zoom_selects_the_arterial(self) -> None:
        # Zoom on this endpoint chooses which road answers, not how finely one
        # road is drawn. At 22 a third of the zones matched residential lanes
        # and read as uncovered. If this is ever raised again, the flat-zone
        # investigation in the module docstring is what to read first.
        assert "/absolute/12/json" in tomtom.ENDPOINT
