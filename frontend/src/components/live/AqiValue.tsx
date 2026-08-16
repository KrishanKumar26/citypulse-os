import type { AirProvenance } from "@/lib/api/types";
import { PROVENANCE_MARK, provenanceTooltip } from "@/lib/provenance";
import { cn } from "@/components/ui";

/**
 * An AQI in a table cell, with a short mark for where it came from.
 *
 * Shared by the two zone tables — Command Center's and Live Intelligence's —
 * because they are the same fact rendered twice, and the first version of this
 * marker went into only one of them. A reader comparing rows in either place
 * sees two numbers that look like the same kind of fact, and on this deployment
 * one of them can be an instrument's and the other a model's.
 *
 * A short word rather than a badge: a coloured pill on every row of a sixty-two
 * row table would drown the risk column, which is what these tables are for.
 * A word rather than an initial, because "measured" and "modelled" share theirs
 * — this column rendered both as "m" and so could not separate the two states
 * the platform works hardest to keep apart. The exact term and its definition
 * are on hover, and spelled out beside the number in the zone detail panel.
 *
 * Only the generated state is coloured. Measured and modelled are both real,
 * and marking one of them would suggest a reader should discount it.
 */
export function AqiValue({
  aqi,
  source,
}: {
  aqi: number | null;
  source: AirProvenance | null;
}) {
  if (aqi === null) return <span className="text-content-disabled">—</span>;

  return (
    <span className="text-content-secondary">
      {aqi}
      {source && (
        <span
          title={provenanceTooltip(source)}
          className={cn(
            "ml-1 text-[10px] uppercase",
            source === "SYNTHETIC" ? "text-status-moderate" : "text-content-tertiary",
          )}
        >
          {PROVENANCE_MARK[source]}
        </span>
      )}
    </span>
  );
}

/**
 * How loaded a road is, in whichever of the two ways it was actually measured.
 *
 * This column cannot be one number. A generated window knows how full the road
 * is; a window covered by a probe feed knows how fast it is moving and never
 * how full, because nothing counted the vehicles on it. Migration V22 records
 * why the conversion between them was measured and rejected.
 *
 * So the unit is written out — "full" against "of free flow" — rather than
 * both arriving as a bare percentage under one heading. Two meanings sharing a
 * column and a `%` is how this codebase once put an occupancy of 1.62 on screen
 * as "162% of capacity" beside a sentence calling it 1.62, and a reader
 * comparing two rows here would have no way to tell which question each answered.
 *
 * Null is "no reading", not zero, and says so in words for the same reason the
 * condition column does.
 */
export function TrafficValue({
  occupancy,
  speedRatio,
  source,
}: {
  /** Share of road capacity in use, 0–1. Generated windows only. */
  occupancy: number | null;
  /** Current speed over free flow, 0–1. Real feeds only. */
  speedRatio: number | null;
  source: AirProvenance | null;
}) {
  // Occupancy first: it is the platform's native measure, and a row carrying
  // both is a writer's bug worth showing consistently rather than at random.
  const shown =
    occupancy !== null
      ? { value: Math.round(occupancy * 100), unit: "full" }
      : speedRatio !== null
        ? { value: Math.round(speedRatio * 100), unit: "of free flow" }
        : null;

  if (shown === null) {
    return <span className="text-[11px] text-content-disabled">No reading</span>;
  }

  return (
    <span className="text-content-secondary">
      {shown.value}%{" "}
      <span className="text-[10px] text-content-tertiary">{shown.unit}</span>
      {source && (
        <span
          title={provenanceTooltip(source)}
          className={cn(
            "ml-1 text-[10px] uppercase",
            source === "SYNTHETIC" ? "text-status-moderate" : "text-content-tertiary",
          )}
        >
          {PROVENANCE_MARK[source]}
        </span>
      )}
    </span>
  );
}
