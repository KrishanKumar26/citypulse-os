"""Daily analytics DAG (PRD §25).

    Load → Transform → Aggregate → Generate metrics

Rebuilds the dbt marts from whatever the streaming pipeline landed, then
publishes freshness and quality figures.

This is the DAG that actually runs on a schedule. `dbt build` runs models and
their tests together, so a model that produces bad data never reaches the marts
the API reads — the run fails first.

There is deliberately no model-training DAG here. Forecasting is Phase 5 and
nothing trains a model yet; a DAG whose tasks would be placeholders is the kind
of fake production functionality PRD §39.4 rules out.
"""

from __future__ import annotations

import os
from datetime import datetime, timedelta

from airflow import DAG
from airflow.operators.bash import BashOperator
from airflow.operators.python import PythonOperator


DATA_ENGINEERING_HOME = os.environ.get("CITYPULSE_DE_HOME", "/opt/citypulse/data-engineering")
DBT_BIN = f"{DATA_ENGINEERING_HOME}/.venv/bin/dbt"
DBT_DIR = f"{DATA_ENGINEERING_HOME}/dbt"

DEFAULT_ARGS = {
    "owner": "citypulse-data",
    "retries": 2,
    "retry_delay": timedelta(minutes=10),
    "depends_on_past": False,
    "email_on_failure": False,
}


def publish_freshness(**context) -> None:
    """Record how current the curated layer is.

    Written as a row rather than a log line so "when was this last good" is a
    query. A freshness number that only exists in a scheduler log is not
    available to the dashboard that needs to caveat a stale figure.
    """
    import psycopg
    from psycopg.rows import dict_row

    dsn = os.environ["CITYPULSE_PG_DSN"]
    max_staleness_hours = float(os.environ.get("CITYPULSE_MAX_STALENESS_HOURS", "6"))

    with psycopg.connect(dsn, row_factory=dict_row) as connection:
        with connection.cursor() as cursor:
            cursor.execute(
                """
                SELECT
                    max(window_start) AS newest_window,
                    count(*)          AS window_count,
                    count(DISTINCT zone_id) AS zones_covered
                FROM zone_metrics
                """
            )
            row = cursor.fetchone()

            if row["newest_window"] is None:
                raise ValueError(
                    "zone_metrics is empty. Either ingestion is not running or the "
                    "curated load failed; the marts built from this are meaningless."
                )

            cursor.execute(
                "SELECT extract(epoch from (now() - %s::timestamptz)) / 3600 AS hours",
                (row["newest_window"],),
            )
            staleness = float(cursor.fetchone()["hours"])

            cursor.execute(
                """
                INSERT INTO data_quality_metrics
                    (source_id, stage, window_start, window_end,
                     records_received, records_valid, records_rejected,
                     records_duplicate, records_late, validity_ratio, max_lag_seconds)
                VALUES (NULL, 'AGGREGATE', date_trunc('day', now()),
                        date_trunc('day', now()) + interval '1 day',
                        %s, %s, 0, 0, 0, 1.0, %s)
                ON CONFLICT (source_id, stage, window_start, window_end) DO UPDATE SET
                    records_received = EXCLUDED.records_received,
                    records_valid    = EXCLUDED.records_valid,
                    max_lag_seconds  = EXCLUDED.max_lag_seconds,
                    computed_at      = now()
                """,
                (row["window_count"], row["window_count"], int(staleness * 3600)),
            )
        connection.commit()

    print(
        f"curated freshness: newest window {row['newest_window']}, "
        f"{staleness:.1f}h old, {row['window_count']} windows across "
        f"{row['zones_covered']} zones"
    )

    if staleness > max_staleness_hours:
        raise ValueError(
            f"curated data is {staleness:.1f}h old, beyond the "
            f"{max_staleness_hours}h threshold. The marts were rebuilt, but they "
            f"are rebuilt from stale input — check that ingestion is running."
        )


with DAG(
    dag_id="citypulse_daily_analytics",
    description="Rebuild dbt marts from curated data and publish quality metrics.",
    default_args=DEFAULT_ARGS,
    # 02:00 UTC — after midnight in the seeded cities' timezone (UTC+5:30), so a
    # run covers a whole local day rather than splitting one.
    schedule="0 2 * * *",
    start_date=datetime(2026, 1, 1),
    catchup=False,
    max_active_runs=1,
    tags=["citypulse", "analytics", "dbt"],
) as dag:

    # Source freshness first: rebuilding marts from a feed that stopped
    # yesterday produces a green run and a stale dashboard.
    check_source_freshness = BashOperator(
        task_id="check_source_freshness",
        bash_command=(
            f"cd {DBT_DIR} && DBT_PROFILES_DIR={DBT_DIR} {DBT_BIN} source freshness"
        ),
    )

    # `build` rather than `run` then `test`: build interleaves them, so a failing
    # test stops its dependents instead of letting the whole graph materialise
    # first and reporting the failure afterwards.
    dbt_build = BashOperator(
        task_id="dbt_build",
        bash_command=(
            f"cd {DBT_DIR} && DBT_PROFILES_DIR={DBT_DIR} {DBT_BIN} build"
        ),
    )

    publish_metrics = PythonOperator(
        task_id="publish_freshness_metrics",
        python_callable=publish_freshness,
    )

    check_source_freshness >> dbt_build >> publish_metrics
