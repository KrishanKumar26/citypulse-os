"""Historical ingestion DAG (PRD §25).

    Extract → Validate → Transform → Load → Quality Check

Backfills a window of synthetic history and pushes it through the same
validation, aggregation and load path the streaming job uses. Its purpose is
seeding a fresh environment and re-driving a corrected day, not routine
operation — live data arrives through Kafka, not through this DAG.

The tasks shell out to the same CLIs a developer runs by hand. That is
deliberate: an Airflow task that reimplements the pipeline is a second
implementation to keep in step, and it is the copy nobody runs locally, so it is
the copy that rots.
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator


DATA_ENGINEERING_HOME = os.environ.get("CITYPULSE_DE_HOME", "/opt/citypulse/data-engineering")
PYTHON = f"{DATA_ENGINEERING_HOME}/.venv/bin/python"
STAGING_DIR = os.environ.get("CITYPULSE_STAGING_DIR", "/tmp/citypulse-backfill")

DEFAULT_ARGS = {
    "owner": "citypulse-data",
    # Retries help with a transient database blip. They do not help with bad
    # data, and the loader is idempotent, so a retry re-runs safely.
    "retries": 2,
    "retry_delay": timedelta(minutes=5),
    "depends_on_past": False,
    "email_on_failure": False,
}


def assert_quality(**context) -> None:
    """Fail the run if the batch's validity ratio is below the floor.

    A backfill that silently loaded 40% of its records is worse than one that
    failed: the gap is invisible once the run is green, and every chart built on
    it is quietly wrong.
    """
    import psycopg
    from psycopg.rows import dict_row

    dsn = os.environ["CITYPULSE_PG_DSN"]
    logical_date = context["logical_date"]
    floor = float(os.environ.get("CITYPULSE_MIN_VALIDITY_RATIO", "0.98"))

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                    coalesce(sum(records_received), 0) AS received,
                    coalesce(sum(records_valid), 0)    AS valid,
                    coalesce(sum(records_rejected), 0) AS rejected
                FROM data_quality_metrics
                WHERE stage = 'VALIDATE'
                  AND window_start >= %s::timestamptz
                  AND window_start <  %s::timestamptz + interval '1 day'
                """,
                (logical_date, logical_date),
            )
            row = cursor.fetchone()

    received = row["received"] or 0
    if received == 0:
        raise ValueError(
            f"no records ingested for {logical_date:%Y-%m-%d}; the extract step "
            f"produced nothing and the load would have been a silent no-op"
        )

    ratio = row["valid"] / received
    if ratio < floor:
        raise ValueError(
            f"validity ratio {ratio:.2%} is below the {floor:.2%} floor "
            f"({row['rejected']} of {received} records rejected). "
            f"Inspect ingestion_dlq for the reason codes before re-running."
        )

    print(f"quality check passed: {ratio:.2%} of {received} records valid")


with DAG(
    dag_id="citypulse_historical_ingestion",
    description="Backfill a day of synthetic history through validate → aggregate → load.",
    default_args=DEFAULT_ARGS,
    # Daily, but not scheduled: backfill is an operator decision, triggered for
    # a specific date range. A schedule here would silently re-load history.
    schedule=None,
    start_date=datetime(2026, 1, 1),
    catchup=False,
    max_active_runs=1,
    tags=["citypulse", "ingestion", "backfill"],
) as dag:

    extract = BashOperator(
        task_id="extract",
        bash_command=(
            f"cd {DATA_ENGINEERING_HOME} && "
            f"mkdir -p {STAGING_DIR} && "
            f"{PYTHON} -m generator.main "
            f"--sink jsonl --out {STAGING_DIR}/{{{{ ds }}}}.jsonl "
            f"--no-realtime --tick-seconds 300 "
            # Seeded by date so re-running a day reproduces that day exactly.
            f"--seed {{{{ macros.ds_format(ds, '%Y-%m-%d', '%Y%m%d') }}}} "
            f"--simulate-from {{{{ ds }}}}T00:00:00Z "
            f"--simulate-to {{{{ macros.ds_add(ds, 1) }}}}T00:00:00Z "
            f"--quiet"
        ),
    )

    # Validation, transformation and load are one command because they are one
    # transaction boundary: the runner writes raw rows before the aggregates
    # that summarise them, and splitting the steps across tasks would let a
    # failure leave curated windows with no evidence behind them.
    validate_transform_load = BashOperator(
        task_id="validate_transform_load",
        bash_command=(
            f"cd {DATA_ENGINEERING_HOME} && "
            f"{PYTHON} -m pipeline.local_runner "
            f"--input {STAGING_DIR}/{{{{ ds }}}}.jsonl "
            f"--now {{{{ macros.ds_add(ds, 1) }}}}T00:00:00Z "
            # Backfill is a deliberate historical load, so the streaming
            # watermark is raised explicitly for it.
            f"--max-lateness-hours 26"
        ),
    )

    quality_check = PythonOperator(
        task_id="quality_check",
        python_callable=assert_quality,
    )

    cleanup = BashOperator(
        task_id="cleanup_staging",
        bash_command=f"rm -f {STAGING_DIR}/{{{{ ds }}}}.jsonl",
        # Runs whether or not the quality check passed: the staged file is
        # reproducible from the seed, so keeping it after a failure buys nothing.
        trigger_rule="all_done",
    )

    extract >> validate_transform_load >> quality_check >> cleanup
