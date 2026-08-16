#!/usr/bin/env python
"""Does TomTom's answer for a zone ever change, or is it a constant?

    # one sample, appended; run it repeatedly across a day
    CITYPULSE_PG_DSN=... TOMTOM_API_KEY=... \
        python scripts/probe_tomtom_variance.py --out /tmp/tomtom.jsonl

    # what the samples so far say
    python scripts/probe_tomtom_variance.py --summarise /tmp/tomtom.jsonl

`scripts/probe_tomtom.py` established that all sixty-two zones answer, close
by, at high confidence. It also showed twenty-two of them reporting
`currentSpeed` exactly equal to `freeFlowSpeed`, concentrated on minor roads:
eighteen of the thirty-three zones whose centre snapped to FRC4–FRC7, against
four of the twenty-nine on FRC0–FRC3.

One snapshot cannot tell those two things apart:

    a road that happens to be free-flowing at 10:37 on a Tuesday
    a road TomTom has no live probes for, answering with its reference speed

The first is a working signal. The second is a constant wearing a measurement's
clothing, and feeding it to the anomaly detector would produce a metric that can
never deviate from its own baseline — a zone that is silent forever and looks
healthy doing it. Only time separates them, so this samples and waits.

**Every zone is sampled, not only the flat ones.** The zones already known to
move are the control: if a run reports that nothing anywhere changed, that is
evidence the sampler is broken, not that sixty-two Indian roads held one speed
all day. Without them a null result cannot be distinguished from a bug, which is
the failure this repository has written down more times than any other.

**One sample per invocation, appended to a file.** A process that sleeps for six
hours loses everything to a closed laptop, and the question needs a spread of
hours to answer. Each run is a few seconds; the schedule is the caller's problem
and can be cron, a loop, or a person remembering.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from zoneinfo import ZoneInfo

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
sys.path.insert(0, str(Path(__file__).resolve().parent))

import psycopg  # noqa: E402

from probe_tomtom import active_zones, ask  # noqa: E402

#: Readings are stamped in UTC and reported in IST. The question is about
#: rush hours, and a table of UTC timestamps would have the reader doing
#: arithmetic to find them. `intelligence.detection` buckets by Asia/Kolkata
#: for the same reason.
IST = ZoneInfo("Asia/Kolkata")

#: Below this a difference is not movement. TomTom reports whole km/h, so two
#: samples of a genuinely static segment land on exactly the same ratio; this
#: exists so that a 1 km/h wobble on a fast road is not called congestion.
MOVED = 0.02


def sample(out: Path) -> int:
    dsn = os.environ.get("CITYPULSE_PG_DSN")
    key = os.environ.get("TOMTOM_API_KEY")
    if not dsn or not key:
        print("CITYPULSE_PG_DSN and TOMTOM_API_KEY are both required.")
        return 1

    with psycopg.connect(dsn) as connection:
        zones = active_zones(connection)

    moment = datetime.now(timezone.utc)
    answers = [ask(zone, key) for zone in zones]

    with out.open("a") as handle:
        for a in answers:
            handle.write(json.dumps({
                "at": moment.isoformat(),
                "zone": f"{a.zone.city}/{a.zone.code}",
                "status": a.status,
                "error": a.error,
                "frc": a.frc,
                "current_speed": a.current_speed,
                "free_flow_speed": a.free_flow_speed,
                "confidence": a.confidence,
                "snap_km": a.snap_km,
            }) + "\n")

    ok = sum(1 for a in answers if a.error is None)
    print(f"{moment.astimezone(IST):%Y-%m-%d %H:%M} IST  "
          f"{ok}/{len(answers)} answered, appended to {out}")
    return 0 if ok else 1


def summarise(path: Path) -> int:
    if not path.exists():
        print(f"No samples at {path} yet.")
        return 1

    by_zone: dict[str, list[dict]] = defaultdict(list)
    moments: set[str] = set()
    for line in path.read_text().splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        moments.add(row["at"])
        if row.get("error") is None:
            by_zone[row["zone"]].append(row)

    if not by_zone:
        print("Samples exist but none of them answered.")
        return 1

    print(f"{len(moments)} samples, {len(by_zone)} zones\n")
    stamps = sorted(moments)
    print("  taken at: " + ", ".join(
        f"{datetime.fromisoformat(s).astimezone(IST):%H:%M}" for s in stamps) + " IST\n")

    print(f"{'zone':<24} {'frc':>5} {'n':>3} {'min':>6} {'max':>6} {'span':>6}  verdict")
    print("-" * 74)

    still: list[str] = []
    for zone in sorted(by_zone):
        rows = by_zone[zone]
        ratios = [r["current_speed"] / r["free_flow_speed"] for r in rows
                  if r.get("current_speed") is not None and r.get("free_flow_speed")]
        if not ratios:
            print(f"{zone:<24} {rows[-1].get('frc') or '—':>5} {len(rows):>3} "
                  f"{'—':>6} {'—':>6} {'—':>6}  no usable ratio")
            continue
        low, high = min(ratios), max(ratios)
        span = high - low
        # A zone is only called still once there is more than one sample to be
        # still across. With n=1 the span is trivially zero and says nothing.
        # Two ways to be still, and only one of them is a problem.
        #
        # A zone holding 0.788 across every sample is reporting congestion that
        # did not happen to change; roads do that, and the number is live either
        # way. A zone holding exactly 1.000 is the case this script exists for —
        # it is indistinguishable from TomTom answering with its reference speed
        # because it has no probes there. Calling both "not covered" would
        # condemn working zones on the strength of a quiet half hour.
        at_free_flow = abs(high - 1.0) < MOVED and abs(low - 1.0) < MOVED
        if len(ratios) < 2:
            verdict = "one sample"
        elif span >= MOVED:
            verdict = "moves"
        elif at_free_flow:
            verdict = "STILL at free-flow — suspect"
            still.append(zone)
        else:
            verdict = f"steady at {low:.3f} — live"
        print(f"{zone:<24} {rows[-1].get('frc') or '—':>5} {len(ratios):>3} "
              f"{low:6.3f} {high:6.3f} {span:6.3f}  {verdict}")

    # Zones with a single sample are not evidence of anything, and counting
    # them as "moved" is how one snapshot became "the whole map is covered by
    # live data" the first time this ran. A zone joins the judgement only once
    # it has two readings to differ between.
    judged = [z for z in by_zone
              if len([r for r in by_zone[z]
                      if r.get("current_speed") is not None
                      and r.get("free_flow_speed")]) >= 2]
    if not judged:
        print(f"\n  Nothing judged yet: no zone has two usable samples. "
              f"Take another.")
        return 0

    print(f"\n  {len(judged)} of {len(by_zone)} zones had enough samples to judge.")
    print(f"  {len(still)} sat at free-flow for all {len(stamps)} samples.")
    if not still:
        print(f"  Every judged zone either moved or held a congested value. "
              f"Nothing looks uncovered.")
        return 0

    print("  " + ", ".join(still))
    minor = [z for z in still if (by_zone[z][-1].get("frc") or "") >= "FRC4"]
    print(f"  {len(minor)} of them snapped to a minor road (FRC4–FRC7).")

    # Deliberately not a verdict. Three samples over an hour is a quiet road at
    # midday; the same list after a full evening peak is a road with no probes
    # on it. The distinction is the number of samples, so it is stated rather
    # than concluded around.
    print(f"\n  These are candidates for 'not actually covered', not a finding.")
    print(f"  A road can sit at free-flow honestly. What would settle it is "
          f"these\n  same zones staying at 1.000 through an evening peak — "
          f"until a sample\n  spans one, this list is a watchlist.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", type=Path,
                        help="append one sample of every zone to this JSONL file")
    parser.add_argument("--summarise", type=Path, metavar="FILE",
                        help="report what the samples in FILE say, spending no requests")
    arguments = parser.parse_args()

    if arguments.summarise is not None:
        return summarise(arguments.summarise)
    if arguments.out is not None:
        return sample(arguments.out)
    parser.error("one of --out or --summarise is required")


if __name__ == "__main__":
    raise SystemExit(main())
