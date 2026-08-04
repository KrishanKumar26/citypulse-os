"""Synthetic city generator — command line entry point.

  # Emit to a local file for 60 seconds at one tick per second
  python -m generator.main --sink jsonl --out ../data/raw/events.jsonl --duration 60

  # Backfill three days of history as fast as the machine allows
  python -m generator.main --sink jsonl --out seed.jsonl \
      --simulate-from 2026-07-31T00:00:00Z --simulate-to 2026-08-03T00:00:00Z --no-realtime

  # Stream to Kafka
  python -m generator.main --sink kafka --bootstrap-servers localhost:9092

Rate is configurable per PRD Phase 3's exit criteria, and every event carries
demo_data=true from the moment it is created (PRD §42).
"""

from __future__ import annotations

import argparse
import json
import signal
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path

# Allow `python -m generator.main` from the data-engineering directory without
# an install step; the shared `common` package sits alongside this one.
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from common.events import EventType  # noqa: E402
from generator import catalog as catalog_module  # noqa: E402
from generator.sinks import CountingSink, JsonlSink, KafkaSink, MultiSink, Sink, StdoutSink  # noqa: E402
from generator.world import World  # noqa: E402


DEFAULT_TOPICS = {
    str(EventType.TRAFFIC): "citypulse.traffic.v1",
    str(EventType.WEATHER): "citypulse.weather.v1",
    str(EventType.AIR_QUALITY): "citypulse.air-quality.v1",
    str(EventType.INCIDENT): "citypulse.incidents.v1",
    str(EventType.CITY_EVENT): "citypulse.city-events.v1",
}


def parse_moment(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise argparse.ArgumentTypeError(
            f"{value!r} has no timezone offset; use e.g. 2026-08-03T00:00:00Z"
        )
    return parsed.astimezone(timezone.utc)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        prog="generator",
        description="Generate labelled synthetic city telemetry.",
    )
    parser.add_argument(
        "--sink", choices=("stdout", "jsonl", "kafka"), default="stdout",
        help="Where events go. jsonl is the local stand-in for a Kafka topic.",
    )
    parser.add_argument("--out", type=Path, help="Output file for --sink jsonl.")
    parser.add_argument(
        "--tee", type=Path,
        help="Additionally write every event to this file (useful alongside --sink kafka).",
    )
    parser.add_argument("--bootstrap-servers", default="localhost:9092")
    parser.add_argument(
        "--tick-seconds", type=float, default=10.0,
        help="Simulated seconds between traffic ticks. Lower means more events.",
    )
    parser.add_argument(
        "--slow-feed-every", type=int, default=6,
        help="Emit weather, air quality and city events once per N ticks.",
    )
    parser.add_argument("--duration", type=float, help="Stop after this many wall-clock seconds.")
    parser.add_argument("--max-events", type=int, help="Stop after this many events.")
    parser.add_argument("--simulate-from", type=parse_moment, help="Start of simulated time.")
    parser.add_argument("--simulate-to", type=parse_moment, help="End of simulated time.")
    parser.add_argument(
        "--no-realtime", action="store_true",
        help="Advance simulated time without sleeping. Used for backfill.",
    )
    parser.add_argument("--seed", type=int, help="Seed the RNG for a reproducible run.")
    parser.add_argument("--quiet", action="store_true", help="Suppress the progress summary.")
    return parser


def build_sink(args: argparse.Namespace) -> Sink:
    if args.sink == "jsonl":
        if args.out is None:
            raise SystemExit("--sink jsonl requires --out")
        sink: Sink = JsonlSink(args.out)
    elif args.sink == "kafka":
        sink = KafkaSink(args.bootstrap_servers, DEFAULT_TOPICS)
    else:
        sink = StdoutSink()

    if args.tee is not None:
        sink = MultiSink(sink, JsonlSink(args.tee))
    return sink


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    if args.simulate_from and args.simulate_to and args.simulate_to <= args.simulate_from:
        raise SystemExit("--simulate-to must be after --simulate-from")

    with catalog_module.connect() as connection:
        catalog = catalog_module.load(connection)

    if not catalog.zones:
        raise SystemExit(
            "No active zones found. Run the backend once so Flyway applies "
            "V3__seed_demo_geography.sql before generating events."
        )

    def config_for(code: str) -> dict:
        source = catalog.active_source(code)
        return source.config if source else {}

    # A paused source contributes no config and, for traffic, nothing to emit.
    world = World(
        catalog,
        seed=args.seed,
        traffic_config=config_for("synthetic-traffic"),
        weather_config=config_for("synthetic-weather"),
        air_quality_config=config_for("synthetic-air-quality"),
        incident_config=config_for("synthetic-incidents"),
        city_event_config=config_for("synthetic-city-events"),
    )

    stopping = False

    def request_stop(*_: object) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGINT, request_stop)
    signal.signal(signal.SIGTERM, request_stop)

    tick = timedelta(seconds=args.tick_seconds)
    simulated = args.simulate_from or datetime.now(timezone.utc)
    started_wall = time.monotonic()
    tick_index = 0

    sink = CountingSink(build_sink(args))
    try:
        while not stopping:
            if args.simulate_to and simulated >= args.simulate_to:
                break
            if args.duration and (time.monotonic() - started_wall) >= args.duration:
                break
            if args.max_events and sink.total >= args.max_events:
                break

            emit_slow = tick_index % max(1, args.slow_feed_every) == 0
            for event in world.tick(
                simulated, tick_seconds=args.tick_seconds, emit_slow_feeds=emit_slow
            ):
                sink.emit(event)
                if args.max_events and sink.total >= args.max_events:
                    break

            tick_index += 1
            simulated += tick

            if not args.no_realtime:
                # Keep wall-clock pace with simulated time so a live run produces
                # events at the configured rate rather than as fast as it can.
                target = started_wall + tick_index * args.tick_seconds
                delay = target - time.monotonic()
                if delay > 0:
                    time.sleep(delay)
    finally:
        sink.close()

    if not args.quiet:
        elapsed = time.monotonic() - started_wall
        summary = {
            "events_total": sink.total,
            "by_type": sink.counts,
            "ticks": tick_index,
            "wall_seconds": round(elapsed, 2),
            "simulated_from": (args.simulate_from or "now").__str__(),
            "simulated_to": simulated.isoformat(),
            "events_per_second": round(sink.total / elapsed, 1) if elapsed > 0 else None,
        }
        print(json.dumps(summary, indent=2), file=sys.stderr)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
