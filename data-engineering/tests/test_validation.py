"""Tests for ingestion validation.

The phase exit criterion is that an invalid record reaches the DLQ with a reason
code and never reaches curated storage. These specs cover every reason code the
schema allows, so a rule that stops firing is a test failure rather than a
silent gap.
"""

from __future__ import annotations

import json
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

from common.validation import (
    MAX_EVENT_LATENESS,
    ReasonCode,
    ReferenceData,
    Rejected,
    Valid,
    parse_timestamp,
    validate,
)


NOW = datetime(2026, 8, 3, 12, 0, tzinfo=timezone.utc)

REFERENCE = ReferenceData(
    zone_codes=frozenset({"BLR-WHF", "BLR-KOR"}),
    city_slugs=frozenset({"bengaluru"}),
    source_codes=frozenset(
        {"synthetic-traffic", "synthetic-weather", "synthetic-air-quality",
         "synthetic-incidents", "synthetic-city-events"}
    ),
)


def traffic(**overrides: object) -> str:
    payload = {
        "event_id": "11111111-1111-4111-8111-111111111111",
        "event_type": "TRAFFIC",
        "source_code": "synthetic-traffic",
        "event_time": NOW.isoformat().replace("+00:00", "Z"),
        "schema_version": 1,
        "demo_data": True,
        "zone_code": "BLR-WHF",
        "vehicle_count": 900,
        "average_speed_kph": 32.5,
        "occupancy_ratio": 0.71,
        "congestion_level": "MODERATE",
    }
    payload.update(overrides)
    return json.dumps(payload)


def check(raw: str) -> Valid | Rejected:
    return validate(raw, REFERENCE, now=NOW)


class TestHappyPath:
    def test_valid_traffic_event_passes(self) -> None:
        result = check(traffic())
        assert isinstance(result, Valid)
        assert result.event_type == "TRAFFIC"
        assert result.payload["event_time"] == NOW

    def test_event_time_is_normalised_to_utc(self) -> None:
        result = check(traffic(event_time="2026-08-03T17:30:00+05:30"))
        assert isinstance(result, Valid)
        assert result.payload["event_time"] == NOW


class TestStructure:
    def test_malformed_json(self) -> None:
        result = check("{not json")
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.MALFORMED_JSON
        # The payload has to survive, or the rejection cannot be explained.
        assert result.raw_payload == "{not json"

    def test_json_that_is_not_an_object(self) -> None:
        result = check("[1, 2, 3]")
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.SCHEMA_MISMATCH

    def test_unknown_event_type(self) -> None:
        result = check(traffic(event_type="PARKING"))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.UNSUPPORTED_EVENT_TYPE

    @pytest.mark.parametrize("field", ["event_id", "source_code", "event_time"])
    def test_missing_envelope_field(self, field: str) -> None:
        result = check(traffic(**{field: None}))
        assert isinstance(result, Rejected)
        assert result.reason_code in (
            ReasonCode.MISSING_REQUIRED_FIELD,
            ReasonCode.TIMESTAMP_INVALID,
        )

    @pytest.mark.parametrize("field", ["zone_code", "vehicle_count", "average_speed_kph", "occupancy_ratio"])
    def test_missing_type_specific_field(self, field: str) -> None:
        result = check(traffic(**{field: None}))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.MISSING_REQUIRED_FIELD
        assert field in result.detail


class TestReferences:
    def test_unknown_zone(self) -> None:
        result = check(traffic(zone_code="XXX-NOPE"))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.UNKNOWN_ZONE

    def test_unknown_source(self) -> None:
        result = check(traffic(source_code="rogue-feed"))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.UNKNOWN_SOURCE

    def test_unknown_city_on_weather(self) -> None:
        payload = json.dumps({
            "event_id": "22222222-2222-4222-8222-222222222222",
            "event_type": "WEATHER",
            "source_code": "synthetic-weather",
            "event_time": NOW.isoformat().replace("+00:00", "Z"),
            "city_slug": "atlantis",
            "temperature_c": 28.0,
            "humidity_pct": 60.0,
            "precipitation_mm_h": 0.0,
            "wind_speed_kph": 5.0,
        })
        result = check(payload)
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.UNKNOWN_CITY

    def test_empty_reference_data_skips_reference_checks(self) -> None:
        """An unconfigured runner must not reject everything as unknown."""
        result = validate(traffic(zone_code="ANY"), ReferenceData.empty(), now=NOW)
        assert isinstance(result, Valid)


class TestTimestamps:
    def test_naive_timestamp_rejected(self) -> None:
        result = check(traffic(event_time="2026-08-03T12:00:00"))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.TIMESTAMP_INVALID

    def test_unparseable_timestamp_rejected(self) -> None:
        result = check(traffic(event_time="yesterday"))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.TIMESTAMP_INVALID

    def test_far_future_rejected(self) -> None:
        future = (NOW + timedelta(hours=2)).isoformat().replace("+00:00", "Z")
        result = check(traffic(event_time=future))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.TIMESTAMP_IN_FUTURE

    def test_small_clock_skew_tolerated(self) -> None:
        """Producers' clocks drift; a couple of minutes must not be a rejection."""
        skewed = (NOW + timedelta(minutes=2)).isoformat().replace("+00:00", "Z")
        assert isinstance(check(traffic(event_time=skewed)), Valid)

    def test_beyond_watermark_rejected(self) -> None:
        old = (NOW - MAX_EVENT_LATENESS - timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
        result = check(traffic(event_time=old))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.TIMESTAMP_TOO_OLD

    def test_within_watermark_accepted(self) -> None:
        late = (NOW - MAX_EVENT_LATENESS + timedelta(minutes=1)).isoformat().replace("+00:00", "Z")
        assert isinstance(check(traffic(event_time=late)), Valid)

    def test_parse_timestamp_requires_offset(self) -> None:
        assert parse_timestamp("2026-08-03T12:00:00") is None
        assert parse_timestamp("2026-08-03T12:00:00Z") is not None
        assert parse_timestamp(12345) is None


class TestRanges:
    @pytest.mark.parametrize(
        ("field", "value"),
        [
            ("average_speed_kph", 401),
            ("average_speed_kph", -1),
            ("occupancy_ratio", -0.1),
            ("occupancy_ratio", 11),
            ("vehicle_count", -5),
        ],
    )
    def test_out_of_range_rejected(self, field: str, value: float) -> None:
        result = check(traffic(**{field: value}))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.VALUE_OUT_OF_RANGE

    @pytest.mark.parametrize("literal", ["NaN", "Infinity", "-Infinity"])
    def test_non_finite_numbers_rejected(self, literal: str) -> None:
        """NaN and infinity pass every comparison silently.

        Python's json module accepts these non-standard literals, so they reach
        the validator as real floats and must be caught explicitly — a bare
        range check would let them straight through to the database.
        """
        payload = json.loads(traffic())
        # Substituted textually because json.dumps cannot emit these literals
        # without also making the document invalid for stricter parsers.
        raw = json.dumps(payload).replace('"average_speed_kph": 32.5', f'"average_speed_kph": {literal}')
        assert literal in raw, "substitution failed; the fixture format changed"

        result = check(raw)
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.VALUE_OUT_OF_RANGE

    def test_string_where_number_expected(self) -> None:
        result = check(traffic(average_speed_kph="fast"))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.SCHEMA_MISMATCH

    def test_boolean_is_not_a_number(self) -> None:
        """Python treats True as 1; the validator must not."""
        result = check(traffic(vehicle_count=True))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.SCHEMA_MISMATCH

    def test_bad_enum_value(self) -> None:
        result = check(traffic(congestion_level="TERRIBLE"))
        assert isinstance(result, Rejected)
        assert result.reason_code == ReasonCode.SCHEMA_MISMATCH


class TestDuplicates:
    def test_duplicate_within_batch_rejected(self) -> None:
        seen: set[str] = set()
        first = validate(traffic(), REFERENCE, now=NOW, seen_event_ids=seen)
        second = validate(traffic(), REFERENCE, now=NOW, seen_event_ids=seen)
        assert isinstance(first, Valid)
        assert isinstance(second, Rejected)
        assert second.reason_code == ReasonCode.DUPLICATE_EVENT_ID

    def test_distinct_ids_both_accepted(self) -> None:
        seen: set[str] = set()
        a = validate(traffic(), REFERENCE, now=NOW, seen_event_ids=seen)
        b = validate(
            traffic(event_id="33333333-3333-4333-8333-333333333333"),
            REFERENCE, now=NOW, seen_event_ids=seen,
        )
        assert isinstance(a, Valid) and isinstance(b, Valid)


class TestNeverRaises:
    """`validate` is the pipeline's boundary; an exception there kills a batch."""

    @pytest.mark.parametrize(
        "raw",
        ["", "null", "0", '""', "[]", "{}", '{"event_type":null}',
         '{"event_type":"TRAFFIC"}', "\x00", "🙂"],
    )
    def test_hostile_input_returns_rejection(self, raw: str) -> None:
        result = validate(raw, REFERENCE, now=NOW)
        assert isinstance(result, Rejected)
        assert result.reason_code


class TestSchemaAgreement:
    def test_reason_codes_match_the_database_constraint(self) -> None:
        """Every code the validator emits must be storable.

        A reason code missing from ck_ingestion_dlq_reason would abort the whole
        insert at the moment a bad record arrived — the pipeline would fail
        exactly when it was doing its job.
        """
        migration = (
            Path(__file__).resolve().parents[2]
            / "backend/src/main/resources/db/migration/V4__telemetry_schema.sql"
        )
        sql = migration.read_text(encoding="utf-8")
        constraint = re.search(
            r"CONSTRAINT ck_ingestion_dlq_reason CHECK \(reason_code IN \((.*?)\)\)",
            sql,
            re.DOTALL,
        )
        assert constraint, "ck_ingestion_dlq_reason not found in V4"
        allowed = set(re.findall(r"'([A-Z_]+)'", constraint.group(1)))

        emitted = {
            value for name, value in vars(ReasonCode).items()
            if not name.startswith("_") and isinstance(value, str)
        }
        assert emitted <= allowed, f"validator emits codes the DB rejects: {emitted - allowed}"
