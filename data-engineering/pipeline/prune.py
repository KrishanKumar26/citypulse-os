"""Report what the database is holding, and drop what is past its usefulness.

    python -m pipeline.prune --report          # measure, write nothing
    python -m pipeline.prune --days 7          # drop raw events older than this

The demo refreshes hourly and had no retention of any kind, so every raw event
written since the deployment was still there. That is fine for a while and then
it is not: a free-tier Postgres has a storage ceiling, and reaching it does not
degrade gracefully. Reads keep working and every write fails, so the dashboard
looks healthy while nobody can sign in — which is exactly how it presented.

**The raw event tables go first**, because they have already done their job once
the window they belong to is built. Three days of them is enough to rebuild
recent history and to show the lake layer is real. Thirty was the first guess and
it freed almost nothing: the refresh writes three hours of events every hour, so
the raw tables grow at three times real time and everything in them is days old,
not months.

**The curated windows are kept for thirty days**, which is a change. They were
exempt entirely on the grounds that `zone_metrics` is the platform's memory, and
that held until it was 263 MB of a 489 MB database and the deployment stopped
accepting writes with everything else already pruned. Thirty days is not a guess
either: the detector needs twelve samples in an hour-of-week bucket and a week
supplies exactly twelve, so a month leaves four times the floor. See
CURATED_TABLES below for the arithmetic and for what it costs.

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

# Imported rather than restated. The retention below is derived from this
# number, and a copy of it here would let the detector's floor move without the
# retention that feeds it moving too.
from intelligence.detection import MIN_BASELINE_SAMPLES  # noqa: E402

#: A bucket is one hour of one weekday, and five-minute windows put twelve of
#: them in a week. So this many days is the point at which a bucket holds
#: exactly MIN_BASELINE_SAMPLES and the detector is one sample from declining.
DAYS_PER_BASELINE_FLOOR = 7

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

#: Issued forecasts, on their own clock.
#:
#: 81 MB of the 489 MB, and growing five horizons per zone per run. A forecast
#: has done its work once it has been scored: `forecast_accuracy` keeps the
#: comparison against what actually happened, which is what the accuracy screen
#: and the confidence figures read. Kept a week so a recent forecast can still
#: be inspected beside the outcome it was scored on.
FORECAST_TABLES = (("forecasts", "issued_at"),)

FORECAST_RETENTION_DAYS = 7

#: The curated windows, and the reason this file used to say they were untouchable.
#:
#: `zone_metrics` was exempt for a good reason: it is the platform's memory, the
#: baseline query has no lower bound, and thinning it would quietly starve the
#: detector until it began declining windows for insufficient history. That
#: reasoning was right about the danger and wrong about the number, because it
#: never went and looked at what the detector actually requires.
#:
#: It requires twelve. `intelligence.detection` buckets by hour of week — 168
#: buckets, because Tuesday 09:00 and Sunday 09:00 are not the same hour — and
#: `MIN_BASELINE_SAMPLES` is 12. A bucket collects one hour per week, which at
#: five-minute windows is twelve samples per week. So **one week is the floor
#: exactly**, and thirty days holds roughly forty-eight per bucket: four times
#: what a judgement needs, which is also the figure detection.py's own comment
#: quotes for four weeks.
#:
#: What this does cost is history no baseline reads: charts and the accuracy
#: screen can no longer look back beyond a month. That is a real loss and it is
#: the one being chosen, against a database that stopped accepting writes at
#: 484 MB of 512 with the prune already doing everything else it could — raw
#: events at three days and forecasts at seven freed five megabytes, because
#: `zone_metrics` was 263 MB of the total and untouchable by rule.
CURATED_TABLES = (("zone_metrics", "window_start"),)

CURATED_RETENTION_DAYS = 30

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
    parser.add_argument(
        "--curated-days", type=int, default=CURATED_RETENTION_DAYS,
        help=(f"Keep curated windows newer than this many days "
              f"(default {CURATED_RETENTION_DAYS}). Below 7 the baselines fall "
              f"under {MIN_BASELINE_SAMPLES} samples a bucket and the detector "
              f"starts declining windows."),
    )
    return parser



def _by_size_ascending(connection: psycopg.Connection) -> list[tuple[str, int]]:
    """Prunable tables, smallest first.

    VACUUM FULL needs somewhere to write the surviving rows before it can drop
    the original. Starting with the smallest means each rewrite frees its own
    space and enlarges the room available to the next, which matters when the
    reason for running this at all is that there is almost none.

    Every table this deletes from, `zone_metrics` included. It was left out of
    this list when curated retention was added, and the run that followed
    deleted 61,080 rows and reported the table at 264 MB before and after: a
    DELETE only marks tuples dead, and the storage ceiling counts pages, not
    live rows. The whole prune freed one megabyte and the database stayed full.
    A table pruned but never rewritten is a table pruned for nothing.
    """
    names = [name for name, _ in EVENT_TABLES + FORECAST_TABLES + CURATED_TABLES]
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


def prune(
    connection: psycopg.Connection,
    *,
    days: int,
    tables: tuple[tuple[str, str], ...],
) -> int:
    total = 0
    for table, column in tables:
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

    # Refused rather than warned about. Under a week every hour-of-week bucket
    # falls below the detector's floor, and the failure is silent: anomalies
    # simply stop being raised and the map looks calm. Freeing disk by making
    # the product stop noticing things is the one trade it must not make, and
    # the flag is easier to type than the outage is to diagnose.
    if args.curated_days < DAYS_PER_BASELINE_FLOOR:
        print(f"--curated-days {args.curated_days} would leave every hour-of-week "
              f"bucket under {MIN_BASELINE_SAMPLES} samples, and the detector "
              f"would quietly stop judging windows. {DAYS_PER_BASELINE_FLOOR} is "
              f"the floor; {CURATED_RETENTION_DAYS} is the default.")
        return 1

    with psycopg.connect(dsn) as connection:
        print("before:")
        report(connection)

        if args.report:
            print("  --report: nothing deleted")
            return 0

        print(f"\n  dropping raw events older than {args.days} days:")
        removed = prune(connection, days=args.days, tables=EVENT_TABLES)
        print(f"  dropping forecasts older than {FORECAST_RETENTION_DAYS} days:")
        removed += prune(connection, days=FORECAST_RETENTION_DAYS, tables=FORECAST_TABLES)
        print(f"  dropping curated windows older than {args.curated_days} days "
              f"(baselines need {MIN_BASELINE_SAMPLES} samples a bucket; a week "
              f"supplies exactly that):")
        removed += prune(connection, days=args.curated_days, tables=CURATED_TABLES)
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
                try:
                    cursor.execute(f"VACUUM (FULL, ANALYZE) {table}")  # noqa: S608
                except psycopg.errors.DiskFull:
                    # The rewrite needs the survivors' worth of free space and
                    # the ceiling is the reason this is running. A big table on
                    # a nearly full database cannot be rewritten at all, and
                    # letting that end the prune leaves every later table
                    # untouched too.
                    #
                    # Plain VACUUM returns nothing to the ceiling, which is why
                    # FULL is preferred and why an earlier outage was not fixed
                    # by it. It does something else that matters more here: the
                    # dead pages become reusable by this table, so the next
                    # hourly insert fills a hole instead of extending the file.
                    # DiskFull is raised on extension, so writes start working
                    # again even though the reported size does not move.
                    print(f"      no room to rewrite {table}; plain VACUUM "
                          f"instead — its dead space becomes reusable, so "
                          f"writes stop failing, but the reported size will "
                          f"not fall until a run has room")
                    cursor.execute(f"VACUUM (ANALYZE) {table}")  # noqa: S608
        connection.autocommit = False

        print("\nafter:")
        report(connection)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
