"use client";

import { useState } from "react";
import { cn } from "@/components/ui";
import type { ZoneCondition, ConditionLevel } from "@/lib/api/types";

/**
 * Composite risk per zone, ranked.
 *
 * The Command Center had a map and a table and no chart, so the one question a
 * reader arrives with — *which zones are worst, and by how much?* — took reading
 * eight rows and comparing numbers by eye. Ranked bars answer it at a glance,
 * which is the entire job of the form: magnitude compared across a small set of
 * named things.
 *
 * Colour carries severity, never identity, and never alone: every bar is
 * directly labelled with its zone and its score, so the chart is readable in
 * greyscale, under any colour-vision deficiency, and by a screen reader through
 * the table underneath. The four severity steps are the product's existing
 * status tokens rather than a palette invented here — they are the same colours
 * the map, the badges and the alert list already use, and a chart that recoloured
 * "High" would be teaching a second vocabulary for one screen.
 *
 * Drawn as inline SVG on purpose. A charting library would be the single largest
 * dependency in the bundle for four rectangles, and would need theming back to
 * these tokens anyway.
 */

const LEVEL_FILL: Record<ConditionLevel, string> = {
  NORMAL: "var(--color-status-normal)",
  MODERATE: "var(--color-status-moderate)",
  HIGH: "var(--color-status-high)",
  CRITICAL: "var(--color-status-critical)",
};

const LEVEL_LABEL: Record<ConditionLevel, string> = {
  NORMAL: "Normal",
  MODERATE: "Elevated",
  HIGH: "High",
  CRITICAL: "Critical",
};

const ROW_HEIGHT = 30;
const BAR_HEIGHT = 14;
const LABEL_WIDTH = 116;
const VALUE_WIDTH = 34;

export function ZoneRiskChart({
  zones,
  selectedZoneId,
  onSelectZone,
}: {
  zones: ZoneCondition[];
  selectedZoneId?: string | null;
  onSelectZone?: (zoneId: string) => void;
}) {
  const [hovered, setHovered] = useState<string | null>(null);

  // Only zones that reported. A zone with no telemetry has no risk score, and
  // drawing it as a zero-length bar would rank a dead feed as the safest place
  // in the city — the same lie as rendering an absent speed as 0 km/h.
  const ranked = zones
    .filter((z) => z.hasData && z.riskScore !== null && z.riskLevel !== null)
    .sort((a, b) => Number(b.riskScore) - Number(a.riskScore));

  const silent = zones.length - ranked.length;

  if (ranked.length === 0) {
    return (
      <p className="px-5 py-8 text-center text-[13px] text-content-tertiary">
        No zone reported a risk score in this window.
      </p>
    );
  }

  // Fixed 0–100 domain, not the observed maximum. Scaling to the data would
  // make the worst zone a full bar whether it scored 30 or 95, so a calm city
  // and a critical one would look identical.
  const scale = (score: number) => (score / 100) * 100;

  const height = ranked.length * ROW_HEIGHT;

  return (
    <div>
      <div className="relative overflow-x-auto px-5 pb-1">
        <svg
          width="100%"
          viewBox={`0 0 100 ${height}`}
          preserveAspectRatio="none"
          height={height}
          role="img"
          aria-label={`Composite risk by zone, ${ranked.length} zones, highest first`}
          className="block"
          style={{ minWidth: 320 }}
        >
          {/* Recessive reference lines. Quartiles are enough to read a 0-100
              score against; a full grid would compete with the bars. */}
          {[25, 50, 75].map((tick) => (
            <line
              key={tick}
              x1={tick}
              x2={tick}
              y1={0}
              y2={height}
              stroke="var(--color-line-subtle)"
              strokeWidth={0.15}
              vectorEffect="non-scaling-stroke"
            />
          ))}

          {ranked.map((zone, i) => {
            const score = Number(zone.riskScore);
            const level = zone.riskLevel as ConditionLevel;
            const active = hovered === zone.zoneId || selectedZoneId === zone.zoneId;
            const y = i * ROW_HEIGHT + (ROW_HEIGHT - BAR_HEIGHT) / 2;

            return (
              <g
                key={zone.zoneId}
                onMouseEnter={() => setHovered(zone.zoneId)}
                onMouseLeave={() => setHovered(null)}
                onClick={() => onSelectZone?.(zone.zoneId)}
                className={onSelectZone ? "cursor-pointer" : undefined}
              >
                {/* Hit target spanning the row, so the bar does not have to be
                    struck precisely to be hovered or selected. */}
                <rect x={0} y={i * ROW_HEIGHT} width={100} height={ROW_HEIGHT} fill="transparent" />
                <rect
                  x={0}
                  y={y}
                  width={100}
                  height={BAR_HEIGHT}
                  rx={1}
                  fill="var(--color-surface-hover)"
                  opacity={active ? 0.9 : 0.45}
                />
                <rect
                  x={0}
                  y={y}
                  width={Math.max(scale(score), 0.8)}
                  height={BAR_HEIGHT}
                  rx={1}
                  fill={LEVEL_FILL[level]}
                  opacity={active || !hovered ? 1 : 0.55}
                />
              </g>
            );
          })}
        </svg>

        {/* Labels sit in HTML above the SVG rather than inside it: the viewBox is
            stretched horizontally to fill the card, which would distort any text
            drawn in the same coordinate space. */}
        <div className="pointer-events-none absolute inset-0 px-5" style={{ paddingTop: 0 }}>
          {ranked.map((zone) => {
            const score = Number(zone.riskScore);
            const level = zone.riskLevel as ConditionLevel;
            const active = hovered === zone.zoneId || selectedZoneId === zone.zoneId;
            return (
              <div
                key={zone.zoneId}
                className="flex items-center"
                style={{ height: ROW_HEIGHT }}
              >
                <span
                  className={cn(
                    "truncate text-[11.5px]",
                    active ? "text-content-primary" : "text-content-secondary",
                  )}
                  style={{ width: LABEL_WIDTH }}
                  title={zone.zoneName}
                >
                  {zone.zoneName}
                </span>
                <span className="flex-1" />
                <span
                  className={cn(
                    "tabular text-right text-[11.5px] font-medium",
                    active ? "text-content-primary" : "text-content-secondary",
                  )}
                  style={{ width: VALUE_WIDTH }}
                >
                  {score.toFixed(0)}
                </span>
                <span className="ml-2 w-[52px] text-right text-[10px] text-content-tertiary">
                  {LEVEL_LABEL[level]}
                </span>
              </div>
            );
          })}
        </div>
      </div>

      {silent > 0 && (
        // Stated rather than omitted. A chart of six zones in an eight-zone city
        // is a different claim from a chart of all eight.
        <p className="px-5 pt-2 text-[11px] text-content-tertiary">
          {silent} {silent === 1 ? "zone" : "zones"} reported nothing in this window and
          {silent === 1 ? " is" : " are"} not ranked.
        </p>
      )}
    </div>
  );
}

/**
 * How the city's zones are distributed across severity, as one bar.
 *
 * Answers "is this a bad evening or two bad junctions?", which the ranked chart
 * above cannot: eight bars all in the amber band and two in the red band look
 * similar there and mean very different things.
 */
export function RiskDistribution({ zones }: { zones: ZoneCondition[] }) {
  const reporting = zones.filter((z) => z.hasData && z.riskLevel !== null);
  if (reporting.length === 0) return null;

  const order: ConditionLevel[] = ["NORMAL", "MODERATE", "HIGH", "CRITICAL"];
  const counts = order
    .map((level) => ({
      level,
      count: reporting.filter((z) => z.riskLevel === level).length,
    }))
    .filter((segment) => segment.count > 0);

  return (
    <div className="px-5 pb-4">
      <div className="flex h-2 gap-0.5 overflow-hidden rounded-full">
        {counts.map((segment) => (
          <div
            key={segment.level}
            style={{
              width: `${(segment.count / reporting.length) * 100}%`,
              background: LEVEL_FILL[segment.level],
            }}
            // A 2px gap between fills, so adjacent severities stay separable
            // where their hues are closest.
            className="h-full"
          />
        ))}
      </div>
      <div className="mt-2.5 flex flex-wrap gap-x-4 gap-y-1">
        {counts.map((segment) => (
          <span key={segment.level} className="flex items-center gap-1.5 text-[11px]">
            <span
              aria-hidden="true"
              className="h-2 w-2 shrink-0 rounded-sm"
              style={{ background: LEVEL_FILL[segment.level] }}
            />
            <span className="text-content-secondary">{LEVEL_LABEL[segment.level]}</span>
            <span className="tabular text-content-tertiary">{segment.count}</span>
          </span>
        ))}
      </div>
    </div>
  );
}
