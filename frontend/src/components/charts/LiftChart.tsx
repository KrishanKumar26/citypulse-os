"use client";

import { useState } from "react";
import { cn } from "@/components/ui";
import type { Correlation } from "@/lib/api/types";

/**
 * Measured co-occurrence, plotted against the point where it means nothing.
 *
 * Lift is P(B|A)/P(B): how much more often B happens when A is present than it
 * happens generally. **One is the number that matters** — at a lift of 1 the two
 * conditions are unrelated. A list reading "4.4x, 1.9x, 1.1x" gives no sense of
 * which of those is a finding and which is noise, because the reference point is
 * missing from the page and the reader has to hold it in their head.
 *
 * So the axis is anchored at 1 and drawn: bars grow right from it when a
 * condition raises the odds and left when it lowers them, and a pair sitting on
 * the line is visibly sitting on nothing.
 *
 * Support is drawn too, as the count beneath each bar. A lift of 8 over eleven
 * windows and a lift of 2 over nine hundred are not the same claim, and the
 * larger number is the smaller finding — which the bar alone would say backwards.
 *
 * Nothing here is causal, and the component says so rather than relying on the
 * reader to know. The API returns `impliesCausation: false` on every row for the
 * same reason.
 */

const AXIS_MAX = 5;

export function LiftChart({ correlations }: { correlations: Correlation[] }) {
  const [hovered, setHovered] = useState<string | null>(null);

  const rows = [...correlations]
    .sort((a, b) => Number(b.lift) - Number(a.lift))
    .slice(0, 8);

  if (rows.length === 0) {
    return (
      <p className="px-5 py-8 text-center text-[13px] text-content-tertiary">
        No condition pair has co-occurred often enough to measure.
      </p>
    );
  }

  // Clamped, not scaled to the maximum. A single extreme pair would otherwise
  // compress every other bar into the axis and make a set of real findings look
  // like noise beside it.
  const clamped = (lift: number) => Math.min(Math.max(lift, 0), AXIS_MAX);
  const zeroPct = (1 / AXIS_MAX) * 100;
  const widthPct = (lift: number) => (Math.abs(clamped(lift) - 1) / AXIS_MAX) * 100;

  return (
    <div className="px-5 pb-4 pt-1">
      {/* The axis, with 1 labelled as what it means rather than as a number. */}
      <div className="relative mb-2 h-4">
        {[0, 1, 2, 3, 4, 5].map((tick) => (
          <span
            key={tick}
            className={cn(
              "absolute top-0 -translate-x-1/2 text-[9.5px] tabular",
              tick === 1 ? "font-medium text-content-secondary" : "text-content-disabled",
            )}
            style={{ left: `${(tick / AXIS_MAX) * 100}%` }}
          >
            {tick === 1 ? "1× no link" : `${tick}×`}
          </span>
        ))}
      </div>

      <ul className="space-y-2.5">
        {rows.map((c) => {
          const lift = Number(c.lift);
          const raises = lift >= 1;
          const active = hovered === c.statement;
          const share = c.windowsTotal === 0 ? 0 : (c.windowsWithBoth / c.windowsTotal) * 100;

          return (
            <li
              key={`${c.conditionA}-${c.conditionB}`}
              onMouseEnter={() => setHovered(c.statement)}
              onMouseLeave={() => setHovered(null)}
            >
              <div className="flex items-baseline justify-between gap-3">
                <span className="min-w-0 truncate text-[11.5px] text-content-secondary">
                  {readable(c.conditionA)}
                  <span className="text-content-disabled"> → </span>
                  {readable(c.conditionB)}
                </span>
                <span
                  className={cn(
                    "shrink-0 tabular text-[12px] font-medium",
                    raises ? "text-accent" : "text-content-secondary",
                  )}
                >
                  {lift.toFixed(1)}×
                </span>
              </div>

              <div className="relative mt-1 h-3 rounded-sm bg-surface-hover">
                {/* The line at 1. Everything is read relative to it. */}
                <span
                  aria-hidden="true"
                  className="absolute inset-y-0 w-px bg-line-strong"
                  style={{ left: `${zeroPct}%` }}
                />
                <span
                  className={cn(
                    "absolute inset-y-0 rounded-sm transition-opacity",
                    raises ? "bg-accent-mark" : "bg-status-moderate",
                    active || !hovered ? "opacity-100" : "opacity-50",
                  )}
                  style={
                    raises
                      ? { left: `${zeroPct}%`, width: `${widthPct(lift)}%` }
                      : { right: `${100 - zeroPct}%`, width: `${widthPct(lift)}%` }
                  }
                />
              </div>

              {/* Support. A lift of 8 over eleven windows is a weaker claim than
                  a lift of 2 over nine hundred, and the bar says the opposite. */}
              <p className="mt-1 text-[10px] text-content-tertiary">
                {c.windowsWithBoth.toLocaleString()} of {c.windowsTotal.toLocaleString()} windows had
                both ({share.toFixed(1)}%) · {c.windowsWithA.toLocaleString()} had{" "}
                {readable(c.conditionA).toLowerCase()}
              </p>
            </li>
          );
        })}
      </ul>

      <p className="mt-4 border-t border-line-subtle pt-3 text-[10.5px] leading-relaxed text-content-tertiary">
        These are co-occurrences, not causes. A pair can move together because one
        drives the other, because something else drives both, or by chance over a
        finite number of windows. The platform measures how often they appear
        together and states the count; it does not claim to know why.
      </p>
    </div>
  );
}

/** Turns CONDITION_CODES into something a person reads. */
function readable(condition: string): string {
  const words = condition.replace(/_/g, " ").toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
