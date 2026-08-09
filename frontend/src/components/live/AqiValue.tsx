import type { AirProvenance } from "@/lib/api/types";
import { PROVENANCE_DETAIL, PROVENANCE_LABEL } from "@/lib/provenance";
import { cn } from "@/components/ui";

/**
 * An AQI in a table cell, with a one-letter mark for where it came from.
 *
 * Shared by the two zone tables — Command Center's and Live Intelligence's —
 * because they are the same fact rendered twice, and the first version of this
 * marker went into only one of them. A reader comparing rows in either place
 * sees two numbers that look like the same kind of fact, and on this deployment
 * one of them can be an instrument's and the other a model's.
 *
 * One letter rather than a badge: a coloured pill on every row of a sixty-two
 * row table would drown the risk column, which is what these tables are for.
 * The full wording is on hover, and spelled out beside the number in the zone
 * detail panel.
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
          title={PROVENANCE_DETAIL[source]}
          className={cn(
            "ml-1 text-[10px] uppercase",
            source === "SYNTHETIC" ? "text-status-moderate" : "text-content-tertiary",
          )}
        >
          {PROVENANCE_LABEL[source].charAt(0)}
        </span>
      )}
    </span>
  );
}
