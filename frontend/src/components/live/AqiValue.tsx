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
