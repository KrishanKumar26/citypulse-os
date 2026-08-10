"""Report what the database is holding, and drop what is past its usefulness.

    python -m pipeline.prune --report          # measure, write nothing
    python -m pipeline.prune --days 7          # drop raw events older than this

The demo refreshes hourly and had no retention of any kind, so every raw event
written since the deployment was still there. That is fine for a while and then
it is not: a free-tier Postgres has a storage ceiling, and reaching it does not
degrade gracefully. Reads keep working and every write fails, so the dashboard
looks healthy while nobody can sign in — which is exactly how it presented.

**The curated windows are kept.** `zone_metrics` is what every screen reads,
what the baselines are learned from and what the forecasts are scored against;
it is small, one row per zone per five minutes. What grows without bound is the
raw event tables behind it, and those have already done their job once the
window they belong to is built. Three days of them is enough to rebuild recent history and to show the lake
layer is real. Thirty was the first guess and it freed almost nothing: the
refresh writes three hours of events every hour, so the raw tables grow at three
times real time and everything in them is days old, not months.

`--report` is the default posture: it prints the sizes and touches nothing, so
the decision to delete is always made against a measurement.
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import psycopg  # noqa: E402
from psycopg.rows import tuple_row  # noqa: E402

#: Raw event tables, oldest-first prunable. Curated and reference tables are
#: deliberately absent: losing zone_metrics would lose every chart's history,
#: and losing a catalogue table would break the pipeline outright.
EVENT_TABLES = (
    ("traffic_events", "event_time"),
    ("air_quality_events", "event_time"),
    ("weather_events", "event_time"),
    ("incident_events", "reported_at"),
    ("city_events", "starts_at"),
    ("dead_letter_events", "received_at"),
)

DEFAULT_RETENTION_DAYS = 3


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="prune",
        description="Report database size and drop raw events past their retention.",
    )
    parser.add_argument(
        "--report", action="store_true",
        help="Measure and print only. This is what runs when nothing else is asked for.",
    )
    parser.add_argument(
        "--days", type=int, default=DEFAULT_RETENTION_DAYS,
        help=f"Keep raw events newer than this many days (default {DEFAULT_RETENTION_DAYS}).",
    )
    return parser



def _by_size_ascending(connection: psycopg.Connection) -> list[tuple[str, int]]:
    """Prunable tables, smallest first.

    VACUUM FULL needs somewhere to write the surviving rows before it can drop
    the original. Starting with the smallest means each rewrite frees its own
    space and enlarges the room available to the next, which matters when the
    reason for running this at all is that there is almost none.
    """
    names = [name for name, _ in EVENT_TABLES]
    with connection.cursor(row_factory=tuple_row) as cursor:
        cursor.execute(
            """
            SELECT relname, pg_total_relation_size(c.oid)
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind = 'r' AND relname = ANY(%s)
             ORDER BY pg_total_relation_size(c.oid) ASC
            """,
            (names,),
        )
        return cursor.fetchall()


def report(connection: psycopg.Connection) -> None:
    with connection.cursor(row_factory=tuple_row) as cursor:
        cursor.execute("SELECT pg_size_pretty(pg_database_size(current_database()))")
        print(f"  database total: {cursor.fetchone()[0]}")

        cursor.execute("""
            SELECT relname,
                   pg_size_pretty(pg_total_relation_size(c.oid)),
                   pg_total_relation_size(c.oid)
              FROM pg_class c
              JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = 'public' AND c.relkind = 'r'
             ORDER BY pg_total_relation_size(c.oid) DESC
             LIMIT 8
        """)
        print("  largest tables:")
        for name, pretty, _ in cursor.fetchall():
            print(f"    {name:<26} {pretty}")


def prune(connection: psycopg.Connection, *, days: int) -> int:
    total = 0
    for table, column in EVENT_TABLES:
        with connection.cursor(row_factory=tuple_row) as cursor:
            # to_regclass rather than a try/except: a table this deployment does
            # not have is not an error, and a failed DELETE would abort the
            # transaction and take the rest of the prune with it.
            cursor.execute("SELECT to_regclass(%s)", (f"public.{table}",))
            if cursor.fetchone()[0] is None:
                continue

            cursor.execute(
                f"DELETE FROM {table} WHERE {column} < now() - %s::interval",  # noqa: S608
                (f"{days} days",),
            )
            removed = cursor.rowcount
            connection.commit()
            total += removed
            if removed:
                print(f"    {table:<26} {removed:>9,} rows removed")
    return total


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    dsn = os.environ.get("CITYPULSE_PG_DSN")
    if not dsn:
        print("CITYPULSE_PG_DSN is required.")
        return 1

    with psycopg.connect(dsn) as connection:
        print("before:")
        report(connection)

        if args.report:
            print("  --report: nothing deleted")
            return 0

        print(f"\n  dropping raw events older than {args.days} days:")
        removed = prune(connection, days=args.days)
        if not removed:
            print("    nothing was past retention")

        # VACUUM FULL, not VACUUM. A plain vacuum marks the dead tuples reusable
        # by the same table and returns nothing to the disk — which is what a
        # storage ceiling counts, so the first run of this deleted thirty
        # thousand rows and left the reported size unchanged at 489 MB.
        #
        # FULL rewrites the table and needs room for a copy of what survives,
        # not of what was there before, so it is safe directly after a large
        # delete and would not be before one. Smallest tables first: each one
        # finishes by returning its own space, which is what makes room for the
        # next.
        connection.commit()
        connection.autocommit = True
        with connection.cursor() as cursor:
            for table, _ in _by_size_ascending(connection):
                print(f"    rewriting {table}")
                cursor.execute(f"VACUUM (FULL, ANALYZE) {table}")  # noqa: S608
        connection.autocommit = False

        print("\nafter:")
        report(connection)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
