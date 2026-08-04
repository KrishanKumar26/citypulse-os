"""Spark Structured Streaming ingestion (PRD §21).

    spark-submit --packages org.apache.spark:spark-sql-kafka-0-10_2.12:3.5.3 \
        --py-files common.zip pipeline/spark_job.py \
        --bootstrap-servers localhost:9092

Reads the topics, validates every record, splits valid from rejected, writes raw
to the lake, aggregates into windowed zone metrics and upserts them into
PostgreSQL. Rejected records go to `ingestion_dlq` with the reason they failed.

The validation and derivation logic is imported from `common/`, unchanged, so
this job and `local_runner.py` cannot disagree about what a valid record is or
what a window contains. The only thing that differs is the distribution around
them — which is exactly what makes a discrepancy between the two diagnosable.

`--source file` swaps Kafka for a directory of JSON files. That is not a toy
mode: it exercises the same parsing, validation, windowing and write path, so
the job can be verified without a broker.
"""

from __future__ import annotations

import argparse
import os
import sys
from datetime import timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql import functions as F
from pyspark.sql.types import (
    BooleanType,
    StringType,
    StructField,
    StructType,
    TimestampType,
)

from common.validation import ReferenceData, Rejected, Valid, parse_timestamp, validate
from generator import catalog as catalog_module
from pipeline import lake, loader
from pipeline.aggregate import aggregate


TOPICS = [
    "citypulse.traffic.v1",
    "citypulse.weather.v1",
    "citypulse.air-quality.v1",
    "citypulse.incidents.v1",
    "citypulse.city-events.v1",
]

# What the validation UDF returns. Declared explicitly because an inferred
# schema on a streaming query is not stable across restarts.
VALIDATION_SCHEMA = StructType([
    StructField("is_valid", BooleanType(), False),
    StructField("event_type", StringType(), True),
    StructField("event_time", TimestampType(), True),
    StructField("reason_code", StringType(), True),
    StructField("reason_detail", StringType(), True),
])


# Kafka connector coordinate. The Scala suffix must match the Spark build —
# 2.13 for Spark 4.x — or the jar loads and then fails at the first read with a
# NoSuchMethodError that says nothing about versions.
KAFKA_CONNECTOR = "org.apache.spark:spark-sql-kafka-0-10_2.13:4.0.0"

# S3A connector for MinIO. hadoop-aws must match the Hadoop that Spark links
# against, and the AWS SDK must match hadoop-aws. Mismatching either produces a
# NoSuchMethodError at the first write rather than a resolution failure at
# startup, which is why both are pinned together rather than left to resolve.
S3A_CONNECTOR = (
    "org.apache.hadoop:hadoop-aws:3.4.1,"
    "software.amazon.awssdk:bundle:2.24.6"
)


def configure_s3a(spark: SparkSession, endpoint: str, access_key: str, secret_key: str) -> None:
    """Point the S3A filesystem at MinIO.

    Applied to the running context rather than the builder because these are
    Hadoop settings, not Spark ones. The containerised job gets the same values
    from docker/spark-defaults.conf; this path exists so the job also works when
    launched directly.
    """
    hadoop = spark.sparkContext._jsc.hadoopConfiguration()
    hadoop.set("fs.s3a.impl", "org.apache.hadoop.fs.s3a.S3AFileSystem")
    hadoop.set("fs.s3a.endpoint", endpoint)
    hadoop.set("fs.s3a.access.key", access_key)
    hadoop.set("fs.s3a.secret.key", secret_key)
    # MinIO addresses buckets by path, not by virtual host. Without this every
    # request goes to `bucket.localhost` and fails to resolve.
    hadoop.set("fs.s3a.path.style.access", "true")
    hadoop.set("fs.s3a.connection.ssl.enabled", "false")


def build_session(
    app_name: str, *, master: str | None = None, packages: str | None = None
) -> SparkSession:
    # Pin both sides of the Python bridge to the interpreter running this file.
    #
    # Executors otherwise launch whatever `python3` resolves to on the host,
    # which on a machine with several installed is rarely the one running the
    # driver. Spark then fails at the first UDF with PYTHON_VERSION_MISMATCH —
    # after the job has started, so it reads as a data problem rather than a
    # configuration one.
    #
    # This has to be an environment variable set before the JVM launches: the
    # equivalent `spark.pyspark.python` config is read too late to affect the
    # worker processes, which is worth knowing because it looks like it should
    # work and silently does not.
    os.environ.setdefault("PYSPARK_PYTHON", sys.executable)
    os.environ.setdefault("PYSPARK_DRIVER_PYTHON", sys.executable)

    builder = SparkSession.builder.appName(app_name)
    if master:
        builder = builder.master(master)
    if packages:
        # Set here rather than relying on a --packages flag, so the job fetches
        # its own connector whether it is launched by spark-submit or run
        # directly as a module. Without it, `--source kafka` fails with an
        # unhelpful "Failed to find data source: kafka".
        builder = builder.config("spark.jars.packages", packages)
    return (
        builder
        # Small partitions: the demo's volume does not justify 200, and the
        # default turns every micro-batch into hundreds of near-empty tasks.
        .config("spark.sql.shuffle.partitions", "8")
        .config("spark.sql.session.timeZone", "UTC")
        # Adaptive execution coalesces the many small partitions a streaming
        # aggregation produces, which is the main defence against small files.
        .config("spark.sql.adaptive.enabled", "true")
        .getOrCreate()
    )


def load_reference(spark: SparkSession) -> tuple[ReferenceData, dict, dict]:
    """Read zones, cities and sources once and broadcast them.

    Validation needs to know which zones exist. Querying PostgreSQL per record
    would put a network round trip in the hot path; broadcasting a few hundred
    codes costs nothing and makes the check local to each executor.
    """
    with catalog_module.connect() as connection:
        catalog = catalog_module.load(connection)

    reference = ReferenceData(
        zone_codes=frozenset(z.code for z in catalog.zones),
        city_slugs=frozenset(c.slug for c in catalog.cities),
        source_codes=frozenset(s.code for s in catalog.sources),
    )
    zone_ids = {z.code: z.id for z in catalog.zones}
    zone_city = {z.code: z.city_slug for z in catalog.zones}
    return reference, zone_ids, zone_city


def make_validation_udf(spark: SparkSession, reference: ReferenceData):
    """A UDF wrapping the shared validator.

    Broadcast rather than captured by closure so the reference sets are shipped
    to each executor once per job instead of once per task.
    """
    broadcast_reference = spark.sparkContext.broadcast(reference)

    def _validate(raw: str | None):
        if raw is None:
            return (False, None, None, "MALFORMED_JSON", "null message value")
        outcome = validate(raw, broadcast_reference.value)
        if isinstance(outcome, Valid):
            return (True, outcome.event_type, outcome.payload["event_time"], None, None)
        assert isinstance(outcome, Rejected)
        return (False, outcome.event_type, outcome.event_time,
                outcome.reason_code, outcome.detail[:500])

    return F.udf(_validate, VALIDATION_SCHEMA)


def read_stream(spark: SparkSession, args: argparse.Namespace) -> DataFrame:
    if args.source == "kafka":
        return (
            spark.readStream.format("kafka")
            .option("kafka.bootstrap.servers", args.bootstrap_servers)
            .option("subscribe", ",".join(TOPICS))
            .option("startingOffsets", args.starting_offsets)
            # Bounds how much one micro-batch may pull, so a backlog is worked
            # through steadily instead of in one batch that exhausts memory.
            .option("maxOffsetsPerTrigger", str(args.max_offsets_per_trigger))
            .load()
            .select(
                F.col("value").cast("string").alias("raw"),
                F.col("topic"),
                F.col("partition").alias("kafka_partition"),
                F.col("offset").alias("kafka_offset"),
            )
        )

    # File source: the same pipeline without a broker.
    #
    # `topic` carries the directory's name rather than its full path. The DLQ
    # column is sized for a topic name, and a truncated absolute path would keep
    # the leading mount point and discard the only part that identifies which
    # input a rejection came from.
    return (
        spark.readStream.format("text")
        .option("maxFilesPerTrigger", "1")
        .load(args.input_path)
        .select(
            F.col("value").alias("raw"),
            F.lit(f"file:{Path(args.input_path).name}").alias("topic"),
            F.lit(None).cast("int").alias("kafka_partition"),
            F.lit(None).cast("long").alias("kafka_offset"),
        )
    )


def write_batch(batch: DataFrame, batch_id: int, *, zone_ids: dict, zone_city: dict,
                window: timedelta, lake_scheme: str, lake_bucket: str) -> None:
    """Persist one micro-batch.

    Runs on the driver by design. Spark has no native upsert, and the ordering
    guarantee the curated layer needs — raw rows stored before the aggregates
    that summarise them — cannot be expressed as two independent sinks. A
    foreachBatch with the tested loader is the honest way to get both.
    """
    rows = batch.collect()
    if not rows:
        return

    valid_rows = [r for r in rows if r["validation"]["is_valid"]]
    rejected_rows = [r for r in rows if not r["validation"]["is_valid"]]

    import json

    payloads = []
    for row in valid_rows:
        payload = json.loads(row["raw"])
        # Re-parse from the payload rather than reusing the Spark column.
        #
        # Spark hands TimestampType back to Python as a *naive* datetime: the
        # value has been converted to the session timezone but carries no
        # tzinfo. Passing that into the aggregator is how an entire feed ends up
        # silently shifted by the session offset — `window_start` refuses naive
        # input for exactly this reason, and it is what caught this.
        #
        # Parsing the original ISO-8601 string with the shared parser keeps this
        # path byte-identical to local_runner's, and removes the dependency on
        # spark.sql.session.timeZone happening to be UTC.
        payload["event_time"] = parse_timestamp(payload["event_time"])
        payloads.append(payload)

    with catalog_module.connect() as connection:
        ids = loader.Ids.load(connection)

        by_type: dict[str, list[dict]] = {}
        for payload in payloads:
            by_type.setdefault(payload["event_type"], []).append(payload)

        for event_type, chunk in by_type.items():
            writer = loader.WRITERS.get(event_type)
            if writer is not None:
                writer(connection, chunk, ids)
        connection.commit()

        if rejected_rows:
            rejections = [
                Rejected(
                    reason_code=r["validation"]["reason_code"],
                    detail=r["validation"]["reason_detail"] or "",
                    raw_payload=r["raw"] or "",
                    event_type=r["validation"]["event_type"],
                    event_time=r["validation"]["event_time"],
                )
                for r in rejected_rows
            ]
            loader.write_dlq(connection, rejections, ids, topic=str(rows[0]["topic"]))
            connection.commit()

        windows = aggregate(payloads, zone_ids=zone_ids, zone_city=zone_city, window=window)
        loader.write_zone_metrics(connection, windows)
        connection.commit()

    print(
        f"batch {batch_id}: {len(valid_rows)} valid, {len(rejected_rows)} rejected, "
        f"{len(windows)} windows"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="spark_job")
    parser.add_argument("--source", choices=("kafka", "file"), default="kafka")
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument("--input-path", help="Directory of JSON files for --source file.")
    parser.add_argument("--checkpoint", default="/tmp/citypulse-spark-checkpoint")
    parser.add_argument("--starting-offsets", default="latest", choices=("latest", "earliest"))
    parser.add_argument("--max-offsets-per-trigger", type=int, default=50_000)
    parser.add_argument("--window-minutes", type=int, default=5)
    parser.add_argument("--lake-scheme", default="s3a", choices=("s3a", "file"))
    parser.add_argument("--lake-bucket", default=lake.DEFAULT_BUCKET)
    parser.add_argument("--master", help="Spark master, e.g. local[2].")
    parser.add_argument(
        "--once", action="store_true",
        help="Process what is available and stop. Used by the Airflow backfill task and by tests.",
    )
    parser.add_argument("--write-lake", action="store_true", help="Also write raw Parquet to the lake.")
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.source == "file" and not args.input_path:
        raise SystemExit("--source file requires --input-path")

    needed = []
    if args.source == "kafka":
        needed.append(KAFKA_CONNECTOR)
    if args.write_lake and args.lake_scheme == "s3a":
        needed.append(S3A_CONNECTOR)

    spark = build_session(
        "citypulse-ingestion",
        master=args.master,
        packages=",".join(needed) if needed else None,
    )
    spark.sparkContext.setLogLevel("WARN")

    if args.write_lake and args.lake_scheme == "s3a":
        access_key = os.environ.get("MINIO_ROOT_USER")
        secret_key = os.environ.get("MINIO_ROOT_PASSWORD")
        if not access_key or not secret_key:
            raise SystemExit(
                "MINIO_ROOT_USER and MINIO_ROOT_PASSWORD must be set to write to "
                "object storage. Source the repository .env; credentials are never "
                "defaulted (PRD §30)."
            )
        configure_s3a(
            spark,
            os.environ.get("MINIO_ENDPOINT", "http://localhost:9000"),
            access_key,
            secret_key,
        )

    reference, zone_ids, zone_city = load_reference(spark)
    validation_udf = make_validation_udf(spark, reference)
    window = timedelta(minutes=args.window_minutes)

    stream = read_stream(spark, args).withColumn("validation", validation_udf(F.col("raw")))

    if args.write_lake:
        # Raw is written before validation filters anything: PRD §22 requires
        # raw to stay reproducible, and that includes the records validation
        # goes on to reject.
        raw_writer = (
            stream.withColumn("event_time", F.col("validation.event_time"))
            .withColumn("dt", F.to_date("event_time"))
            .withColumn("hour", F.date_format("event_time", "HH"))
            .writeStream.format("parquet")
            .partitionBy("dt", "hour")
            .option("path", lake.dataset_root(
                lake.Layer.RAW, "events", bucket=args.lake_bucket, scheme=args.lake_scheme))
            .option("checkpointLocation", f"{args.checkpoint}/raw")
            .outputMode("append")
        )
        # The lake query must honour --once too. Started on the default trigger
        # it would run continuously, and the shutdown below would stop it as soon
        # as the warehouse query finished — truncating the lake write partway
        # through, non-deterministically, on every backfill.
        if args.once:
            raw_writer = raw_writer.trigger(availableNow=True)
        raw_query = raw_writer.start()
    else:
        raw_query = None

    query = (
        stream.writeStream.foreachBatch(
            lambda batch, batch_id: write_batch(
                batch, batch_id,
                zone_ids=zone_ids, zone_city=zone_city, window=window,
                lake_scheme=args.lake_scheme, lake_bucket=args.lake_bucket,
            )
        )
        .option("checkpointLocation", f"{args.checkpoint}/warehouse")
        .outputMode("append")
    )

    if args.once:
        handle = query.trigger(availableNow=True).start()
        handle.awaitTermination()
        # Wait for the lake write to drain rather than stopping it, so a
        # backfill leaves raw and warehouse holding the same batch.
        if raw_query is not None:
            raw_query.awaitTermination()
    else:
        handle = query.start()
        handle.awaitTermination()
        if raw_query is not None:
            raw_query.stop()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
