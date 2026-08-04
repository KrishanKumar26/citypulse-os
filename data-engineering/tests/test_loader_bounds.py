"""The DLQ writer must never be the thing that fails.

Regression cover for a defect the Spark run surfaced: `ingestion_dlq.topic` is
`VARCHAR(120)`, and the writer clamped `reason_detail` and `raw_payload` but not
`topic`. A long source identifier — a filesystem path, or a Kafka topic name,
which Kafka permits up to 249 characters — raised
`StringDataRightTruncation` and aborted the entire micro-batch, taking the valid
records in it down too.

That failure mode is worse than it looks. The DLQ is the error path: it only
runs when something has already gone wrong, so a crash there converts "a few bad
records were quarantined" into "the whole batch failed", and it does so exactly
when the pipeline is under stress from bad input.

These tests assert the clamping directly, without a database, so the bounds
cannot silently drift from the schema again.
"""

from __future__ import annotations

import re
from datetime import datetime, timezone
from pathlib import Path

import pytest

from common.validation import Rejected
from pipeline import loader


MIGRATION = (
    Path(__file__).resolve().parents[2]
    / "backend/src/main/resources/db/migration/V4__telemetry_schema.sql"
)


class RecordingCursor:
    """Captures the rows a writer would send, without touching PostgreSQL."""

    def __init__(self) -> None:
        self.rows: list[tuple] = []
        self.rowcount = 0

    def executemany(self, _sql: str, rows) -> None:
        self.rows = list(rows)
        self.rowcount = len(self.rows)

    def __enter__(self):
        return self

    def __exit__(self, *exc) -> None:
        return None


class RecordingConnection:
    def __init__(self) -> None:
        self.cursor_obj = RecordingCursor()

    def cursor(self, **_kwargs):
        return self.cursor_obj


def rejection(**overrides) -> Rejected:
    payload = {
        "reason_code": "UNKNOWN_ZONE",
        "detail": "zone_code='XX'",
        "raw_payload": '{"zone_code":"XX"}',
        "event_type": "TRAFFIC",
        "event_time": datetime(2026, 8, 3, 12, 0, tzinfo=timezone.utc),
    }
    payload.update(overrides)
    return Rejected(**payload)


def write(rejections, *, topic: str) -> list[tuple]:
    connection = RecordingConnection()
    loader.write_dlq(connection, rejections, ids=None, topic=topic)  # type: ignore[arg-type]
    return connection.cursor_obj.rows


class TestDlqClamping:
    def test_overlong_topic_is_truncated(self) -> None:
        row = write([rejection()], topic="k" * 400)[0]
        assert len(row[5]) == loader._DLQ_TOPIC_MAX

    def test_kafka_maximum_topic_name_fits(self) -> None:
        """Kafka allows 249 characters; the column holds 120."""
        row = write([rejection()], topic="t" * 249)[0]
        assert len(row[5]) <= loader._DLQ_TOPIC_MAX

    def test_long_filesystem_path_fits(self) -> None:
        row = write([rejection()], topic="/" + "/".join(["segment"] * 40))[0]
        assert len(row[5]) <= loader._DLQ_TOPIC_MAX

    def test_overlong_detail_is_truncated(self) -> None:
        row = write([rejection(detail="d" * 5000)], topic="t")[0]
        assert len(row[1]) == loader._DLQ_DETAIL_MAX

    def test_huge_payload_is_truncated_not_dropped(self) -> None:
        """A 10 MB blob must not become a 10 MB row — but must not vanish either."""
        row = write([rejection(raw_payload="x" * 10_000_000)], topic="t")[0]
        assert len(row[2]) == loader._DLQ_PAYLOAD_MAX

    def test_empty_detail_survives(self) -> None:
        row = write([rejection(detail="")], topic="t")[0]
        assert row[1] == ""

    def test_missing_payload_is_null_not_empty_string(self) -> None:
        row = write([rejection(raw_payload="")], topic="t")[0]
        assert row[2] is None

    def test_none_event_type_survives(self) -> None:
        row = write([rejection(event_type=None)], topic="t")[0]
        assert row[3] is None

    def test_overlong_event_type_is_truncated(self) -> None:
        row = write([rejection(event_type="E" * 200)], topic="t")[0]
        assert len(row[3]) == loader._DLQ_EVENT_TYPE_MAX

    def test_no_rejections_writes_nothing(self) -> None:
        assert write([], topic="t") == []

    @pytest.mark.parametrize("hostile", ["\x00 null byte", "🙂" * 200, "line\nbreaks\n" * 50])
    def test_hostile_payloads_do_not_raise(self, hostile: str) -> None:
        row = write([rejection(raw_payload=hostile, detail=hostile)], topic=hostile)[0]
        assert len(row[5]) <= loader._DLQ_TOPIC_MAX
        assert len(row[1]) <= loader._DLQ_DETAIL_MAX


class TestBoundsMatchTheSchema:
    def test_clamps_match_the_migration(self) -> None:
        """The constants must track the column widths they exist to respect."""
        sql = MIGRATION.read_text(encoding="utf-8")
        block = sql[sql.index("CREATE TABLE ingestion_dlq"):]
        block = block[: block.index(");")]

        declared = dict(re.findall(r"(\w+)\s+VARCHAR\((\d+)\)", block))
        assert int(declared["topic"]) == loader._DLQ_TOPIC_MAX
        assert int(declared["reason_detail"]) == loader._DLQ_DETAIL_MAX
        assert int(declared["event_type"]) == loader._DLQ_EVENT_TYPE_MAX
