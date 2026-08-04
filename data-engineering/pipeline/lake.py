"""Object-store layout for the data lake (PRD §22).

    raw/        Exactly what the producer sent, parsed no further than needed to
                partition it. Never rewritten. This is what makes the pipeline
                reproducible: any downstream layer can be rebuilt from here, and
                a bug in transformation costs a reprocess rather than the data.
    processed/  Validated and typed events. Rejects have been split off.
    curated/    Windowed zone metrics — the layer the warehouse loads from.
    features/   Model inputs (Phase 5).
    rejected/   Records that failed validation, with their reason code.

Raw and transformed data never share a prefix. Mixing them is how a reprocess
ends up reading its own output.

Partitioning is by event date and hour. That matches how the data is queried —
almost always "a time range for a zone" — and keeps a day's reprocess to a day's
files rather than a full scan. Zone is deliberately *not* a partition key: 20
zones times 24 hours times 5 event types would produce thousands of tiny files a
day, and small files are the classic way to make an object-store lake slow.
"""

from __future__ import annotations

from datetime import datetime
from common.compat import StrEnum
from typing import Final


class Layer(StrEnum):
    RAW = "raw"
    PROCESSED = "processed"
    CURATED = "curated"
    FEATURES = "features"
    REJECTED = "rejected"


DEFAULT_BUCKET: Final = "citypulse-lake"


def partition_path(
    layer: Layer,
    dataset: str,
    moment: datetime,
    *,
    bucket: str = DEFAULT_BUCKET,
    scheme: str = "s3a",
) -> str:
    """Path for one dataset partition.

    Hive-style `key=value` partitioning, because Spark, dbt's external tables
    and Athena all discover it without being told the layout separately.

    `s3a` is the default scheme: MinIO speaks the S3 API, so the same path works
    locally and against real S3 with only the endpoint changing. Pass
    `scheme="file"` to write to a local directory.
    """
    if moment.tzinfo is None:
        raise ValueError("refusing to partition on a naive datetime")
    stamp = moment.astimezone(tz=None) if scheme == "file" else moment
    date_part = stamp.strftime("%Y-%m-%d")
    hour_part = stamp.strftime("%H")
    prefix = f"{scheme}://{bucket}" if scheme != "file" else f"file://{bucket}"
    return f"{prefix}/{layer}/{dataset}/dt={date_part}/hour={hour_part}"


def dataset_root(
    layer: Layer,
    dataset: str,
    *,
    bucket: str = DEFAULT_BUCKET,
    scheme: str = "s3a",
) -> str:
    """Root of a dataset, for a reader that discovers partitions itself."""
    prefix = f"{scheme}://{bucket}" if scheme != "file" else f"file://{bucket}"
    return f"{prefix}/{layer}/{dataset}"


# Event type to dataset name. Kept explicit rather than lowercasing the enum, so
# renaming an event type in code cannot silently orphan a lake prefix that
# already holds data.
DATASETS: Final[dict[str, str]] = {
    "TRAFFIC": "traffic_events",
    "WEATHER": "weather_events",
    "AIR_QUALITY": "air_quality_events",
    "INCIDENT": "incidents",
    "CITY_EVENT": "city_events",
}
