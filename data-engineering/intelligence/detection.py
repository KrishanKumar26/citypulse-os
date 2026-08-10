"""Anomaly detection statistics (PRD §13).

Pure functions over numbers — no database, no I/O — so the behaviour that
matters can be tested directly rather than inferred from what a job produced.

The central choice here is **robust statistics**. A baseline learned with a mean
and standard deviation from history that contains anomalies absorbs them: the
spikes the detector exists to find raise the "normal" it compares against, and
sensitivity quietly degrades as more anomalies occur. A median barely moves, and
the median absolute deviation is similarly resistant.

The second choice is that **insufficient data produces no answer**. A bucket
with three samples can make any value look extreme or ordinary depending on
which three; declining to judge is the honest response, and PRD §15 requires it
explicitly.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from common.compat import StrEnum
from typing import Sequence
from zoneinfo import ZoneInfo


# Scales MAD to be comparable with a standard deviation on normally distributed
# data, so the deviation score reads like a familiar z-score.
MAD_TO_SIGMA = 1.4826

# Below this a bucket cannot support a judgement. Four weeks of five-minute
# windows gives roughly 48 samples per hour-of-week bucket, so this is a floor
# for sparse zones and short histories rather than a routine constraint.
MIN_BASELINE_SAMPLES = 12

# Deviation thresholds, in robust sigmas.
#
# 3.5 rather than the conventional 3.0: at 3.0 a normally distributed metric
# produces a false positive roughly once every 370 windows, which across 20
# zones and 5 metrics is a few dozen a day — enough noise to make the feed
# ignorable. The evaluation harness measures what this choice actually costs in
# recall rather than assuming.
THRESHOLD_LOW = 3.5
THRESHOLD_MEDIUM = 5.0
THRESHOLD_HIGH = 8.0
THRESHOLD_CRITICAL = 12.0

# A MAD of zero means the metric never varied in this bucket. Dividing by it
# would make any deviation infinite, so a floor proportional to the median is
# used instead — a perfectly flat history still allows *some* natural variation.
MIN_MAD_FRACTION = 0.02

# An anomaly must be materially different, not merely statistically unusual.
#
# Traffic metrics are heavy-tailed: occupancy is a product of peak demand,
# weather and incidents, so its tails are far fatter than a normal distribution
# and a sigma threshold alone over-flags. Measured on four weeks of history, the
# sigma test alone produced a 4% false-positive rate on occupancy — roughly
# eighty times what 3.5 sigma implies for normal data.
#
# Requiring a minimum relative change as well is not a tuning knob fitted to
# that measurement; it is an operational criterion. A window 3.5 sigma from
# normal but only 8% different from it is a statistical curiosity, not something
# anyone would act on, and a feed full of those is a feed that gets muted.
MIN_RELATIVE_CHANGE = 0.20


class AnomalyType(StrEnum):
    SPIKE = "SPIKE"
    DROP = "DROP"
    SUSTAINED_SHIFT = "SUSTAINED_SHIFT"


class Severity(StrEnum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


@dataclass(slots=True, frozen=True)
class Baseline:
    """What a zone normally does for one metric at one hour of the week."""

    metric: str
    hour_of_week: int
    median: float
    mad: float
    p10: float
    p90: float
    sample_count: int

    @property
    def is_usable(self) -> bool:
        return self.sample_count >= MIN_BASELINE_SAMPLES

    @property
    def effective_mad(self) -> float:
        """MAD with a floor, so a flat history cannot make every point extreme."""
        return max(self.mad, abs(self.median) * MIN_MAD_FRACTION, 1e-6)


@dataclass(slots=True, frozen=True)
class Detection:
    """A judged window. `is_anomaly` False still carries the score."""

    is_anomaly: bool
    deviation_score: float
    anomaly_type: AnomalyType | None
    severity: Severity | None
    percent_change: float | None
    explanation: str


@dataclass(slots=True, frozen=True)
class InsufficientData:
    """No judgement was possible, and why.

    A distinct type rather than a None or a False, because "we looked and found
    nothing" and "we could not look" are different facts. Collapsing them would
    let a zone with no baseline read as a zone behaving normally.
    """

    reason: str
    sample_count: int


def median(values: Sequence[float]) -> float:
    ordered = sorted(values)
    n = len(ordered)
    if n == 0:
        raise ValueError("median of an empty sequence")
    mid = n // 2
    return ordered[mid] if n % 2 else (ordered[mid - 1] + ordered[mid]) / 2.0


def median_absolute_deviation(values: Sequence[float]) -> float:
    if not values:
        raise ValueError("MAD of an empty sequence")
    centre = median(values)
    return median([abs(v - centre) for v in values])


def percentile(values: Sequence[float], fraction: float) -> float:
    if not values:
        raise ValueError("percentile of an empty sequence")
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, int(round(fraction * (len(ordered) - 1)))))
    return ordered[index]


def hour_of_week(moment: datetime, timezone: str = "Asia/Kolkata") -> int:
    """0 = Monday 00:00 through 167 = Sunday 23:00, in the city's own timezone.

    168 buckets rather than 24 because Tuesday 09:00 and Sunday 09:00 are not
    the same city, and a detector that treats them alike will flag every quiet
    weekend morning as a drop.
    """
    local = moment.astimezone(ZoneInfo(timezone))
    return local.weekday() * 24 + local.hour


def learn_baseline(
    metric: str,
    bucket_hour: int,
    values: Sequence[float],
) -> Baseline | None:
    """Summarise one (metric, hour-of-week) bucket.

    Returns None for an empty bucket rather than a zeroed baseline: a baseline
    of zero would make every real reading look like an enormous spike.
    """
    usable = [v for v in values if v is not None]
    if not usable:
        return None

    return Baseline(
        metric=metric,
        hour_of_week=bucket_hour,
        median=median(usable),
        mad=median_absolute_deviation(usable),
        p10=percentile(usable, 0.10),
        p90=percentile(usable, 0.90),
        sample_count=len(usable),
    )


def _severity(score: float) -> Severity:
    if score >= THRESHOLD_CRITICAL:
        return Severity.CRITICAL
    if score >= THRESHOLD_HIGH:
        return Severity.HIGH
    if score >= THRESHOLD_MEDIUM:
        return Severity.MEDIUM
    return Severity.LOW



def explain(
    *,
    label: str,
    observed: float,
    baseline_median: float,
    samples: int,
    direction: str,
    percent_change: float | None,
    metric: str | None = None,
) -> str:
    """What is happening here, for the person deciding whether to act.

    Its own function because two callers must produce it identically: the
    detector, when it judges a window, and `intelligence.rephrase`, which
    rebuilds it for rows written before the wording changed.

    **It states the situation, not the method.** It said "20.0 standard
    deviations below the normal 43.23", then "far enough out that it is unlikely
    to be ordinary variation. Compared against 12 past readings" — the second
    was plainer English describing the same thing: how the detection was made.
    A duty officer reading a critical row needs to know how bad it is against
    what is normal, and everything about medians, spreads and sample counts is
    the evidence for that, not the message. The evidence is still on the row and
    still shown, in the footnote, on hover.

    **It speaks in multiples, which also fixes a unit bug.** The card scales
    occupancy by 100 to show "162% of capacity" while this sentence carried the
    raw 1.62, so one card showed the same fact as two different numbers. A ratio
    has no units to disagree about — 1.62 against 0.53 is 3.1x whichever way
    either is displayed.
    """
    if not baseline_median:
        # No usable baseline to compare against; say the reading and stop
        # rather than dividing by zero or implying a comparison there is none of.
        return f"{label} is {observed:,.2f}. There is no usual level for this zone at this hour yet."

    ratio = observed / baseline_median
    phrasing = _PHRASING_ABOVE if ratio >= 1 else _PHRASING_BELOW
    fallback = (
        "{label} is {mult} the usual for this time of day."
        if ratio >= 1
        else "{label} is at {mult} of the usual for this time of day."
    )
    amount = f"{ratio:.1f}x" if ratio >= 1 else f"{ratio * 100:.0f}%"
    return phrasing.get(metric or "", fallback).format(label=label, mult=amount)


#: How each metric reads as a situation rather than a measurement.
#:
#: Two sets, because English does not compare upward and downward with the same
#: words: roads are "3.1x as full as usual" but "at 37% of their usual fullness",
#: and one template forced to serve both produces a sentence nobody would say.
#: Keyed by the metric code so each can name the thing the way an operator would
#: say it out loud rather than the way the column is named. The fallbacks cover
#: a metric added later — duller, never wrong.
_PHRASING_ABOVE = {
    "occupancy_ratio": "Roads are {mult} as full as usual for this time of day.",
    "average_speed_kph": "Traffic is moving {mult} the usual speed for this time of day.",
    "vehicle_count": "There are {mult} as many vehicles as usual for this time of day.",
    "risk_score": "Overall risk is {mult} the usual for this time of day.",
}

_PHRASING_BELOW = {
    "occupancy_ratio": "Roads are at {mult} of their usual fullness for this time of day.",
    "average_speed_kph": "Traffic is moving at {mult} of the usual speed for this time of day.",
    "vehicle_count": "There are {mult} as many vehicles as usual for this time of day.",
    "risk_score": "Overall risk is at {mult} of the usual for this time of day.",
}


def detect(
    observed: float,
    baseline: Baseline,
    *,
    metric_label: str | None = None,
    metric: str | None = None,
) -> Detection | InsufficientData:
    """Judge one observation against what this zone normally does.

    The explanation is built here rather than at render time so it is stored
    with the anomaly and stays true after the code changes. `metric` is the
    column code and only reaches `explain`, which uses it to name the thing in
    the words someone would say out loud; the judgement itself does not vary by
    metric.
    """
    if not baseline.is_usable:
        return InsufficientData(
            reason=(
                f"only {baseline.sample_count} historical windows for this zone and hour; "
                f"{MIN_BASELINE_SAMPLES} are needed before a deviation means anything"
            ),
            sample_count=baseline.sample_count,
        )

    label = metric_label or baseline.metric.replace("_", " ")
    deviation = abs(observed - baseline.median) / (baseline.effective_mad * MAD_TO_SIGMA)

    percent_change = (
        (observed / baseline.median - 1.0) * 100.0 if baseline.median else None
    )

    # Both tests must pass: statistically unusual *and* materially different.
    # Either alone produces a feed nobody reads — the sigma test flags harmless
    # wobble on heavy-tailed metrics, and a relative-change test alone flags
    # every ordinary swing on a metric that naturally varies a lot.
    relative_change = (
        abs(observed - baseline.median) / abs(baseline.median) if baseline.median else float("inf")
    )
    material = relative_change >= MIN_RELATIVE_CHANGE

    if deviation < THRESHOLD_LOW or not material:
        reason = (
            f"within the usual range for this zone at this hour"
            if deviation < THRESHOLD_LOW
            else f"unusual statistically but only {relative_change:.0%} from normal"
        )
        return Detection(
            is_anomaly=False,
            deviation_score=round(deviation, 4),
            anomaly_type=None,
            severity=None,
            percent_change=None if percent_change is None else round(percent_change, 2),
            explanation=(
                f"{label} of {observed:,.2f} is {reason} (normally {baseline.median:,.2f})."
            ),
        )

    kind = AnomalyType.SPIKE if observed > baseline.median else AnomalyType.DROP
    direction = "above" if kind is AnomalyType.SPIKE else "below"
    # Round once, then use that everywhere. The sentence was formatted from the
    # raw value and the record stored the rounded one, so a change landing on a
    # half — 122.5 — was written as "+123%" in the prose and 122.5 on the row.
    # One is displayed and the other is what a rebuild or an export would use,
    # and they disagreed by a whole percent.
    stored_change = None if percent_change is None else round(percent_change, 2)

    return Detection(
        is_anomaly=True,
        deviation_score=round(deviation, 4),
        anomaly_type=kind,
        severity=_severity(deviation),
        percent_change=stored_change,
        explanation=explain(
            metric=metric,
            label=label,
            observed=observed,
            baseline_median=baseline.median,
            samples=baseline.sample_count,
            direction=direction,
            percent_change=stored_change,
        ),
    )


def detect_sustained(
    recent: Sequence[float],
    baseline: Baseline,
    *,
    min_windows: int = 6,
    metric_label: str | None = None,
) -> Detection | InsufficientData | None:
    """Flag a level that has moved and stayed moved.

    A single window crossing the threshold is a spike; six consecutive windows
    sitting on the wrong side of it is a different situation — congestion that
    has settled in rather than a burst that will clear. Operationally they call
    for different responses, so they are reported as different types.

    Returns None when there is not enough recent history to judge persistence,
    which is not the same as finding nothing.
    """
    if len(recent) < min_windows:
        return None
    if not baseline.is_usable:
        return InsufficientData(
            reason=f"only {baseline.sample_count} historical windows for this bucket",
            sample_count=baseline.sample_count,
        )

    scale = baseline.effective_mad * MAD_TO_SIGMA
    window = list(recent)[-min_windows:]

    # Every window must deviate, and all in the same direction. A set that
    # alternates above and below is noise, not a shift.
    above = all((v - baseline.median) / scale > 2.0 for v in window)
    below = all((baseline.median - v) / scale > 2.0 for v in window)
    if not (above or below):
        return None

    mean_recent = sum(window) / len(window)
    deviation = abs(mean_recent - baseline.median) / scale
    percent_change = (
        (mean_recent / baseline.median - 1.0) * 100.0 if baseline.median else None
    )
    label = metric_label or baseline.metric.replace("_", " ")
    direction = "above" if above else "below"

    return Detection(
        is_anomaly=True,
        deviation_score=round(deviation, 4),
        anomaly_type=AnomalyType.SUSTAINED_SHIFT,
        severity=_severity(deviation),
        percent_change=None if percent_change is None else round(percent_change, 2),
        explanation=(
            f"{label} has stayed {direction} normal for {min_windows} readings in a "
            f"row, averaging {mean_recent:,.2f} against a usual "
            f"{baseline.median:,.2f}. This has settled in rather than being a "
            f"passing spike."
        ),
    )
