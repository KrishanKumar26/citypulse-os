"use client";

import { Skeleton } from "@/components/ui";
import type { CityKpis, ConditionLevel } from "@/lib/api/types";
import { formatNumber } from "@/lib/format";

/**
 * The KPI tiles from PRD §8.
 *
 * Every tile can render three states, and the third is the one that matters:
 * a measured value, a loading skeleton, and *not measured*. A dashboard that
 * shows "0 km/h" when no traffic reading exists is not merely imprecise — it
 * reports a dead feed as gridlock, which is the opposite of the truth.
 */

const LEVEL_TEXT: Record<ConditionLevel, string> = {
  NORMAL: "text-status-normal",
  MODERATE: "text-status-moderate",
  HIGH: "text-status-high",
  CRITICAL: "text-status-critical",
};

interface Tile {
  label: string;
  /** Null renders as "not measured" rather than as a zero. */
  value: string | null;
  unit?: string;
  level?: ConditionLevel | null;
  /** Shown under the value to qualify what it covers. */
  note?: string;
}

function percent(ratio: string | null): string | null {
  if (ratio === null) return null;
  return (Number(ratio) * 100).toFixed(0);
}

export function KpiRow({ kpis, loading }: { kpis: CityKpis | null; loading: boolean }) {
  const tiles: Tile[] = [
    {
      label: "Congestion",
      value: percent(kpis?.averageCongestion ?? null),
      unit: "% of capacity",
      level: kpis?.overallRiskLevel ?? null,
    },
    {
      label: "Average speed",
      value: kpis?.averageSpeedKph ? Number(kpis.averageSpeedKph).toFixed(1) : null,
      unit: "km/h",
    },
    {
      label: "Vehicles",
      value: kpis?.totalVehicleCount != null ? formatNumber(kpis.totalVehicleCount) : null,
      unit: "in window",
    },
    {
      label: "Air quality",
      value: kpis?.averageAqi != null ? String(kpis.averageAqi) : null,
      unit: "AQI",
    },
    {
      label: "Weather",
      value: kpis?.temperatureC ? Number(kpis.temperatureC).toFixed(1) : null,
      unit: "°C",
      note: kpis?.weatherCondition?.replace(/_/g, " ").toLowerCase(),
    },
    {
      label: "Active incidents",
      value: kpis ? String(kpis.activeIncidents) : null,
    },
    {
      label: "Open alerts",
      value: kpis ? String(kpis.activeAlerts) : null,
      level: kpis && kpis.activeAlerts > 0 ? "HIGH" : "NORMAL",
    },
    {
      label: "Composite risk",
      value: kpis?.averageRiskScore ? Number(kpis.averageRiskScore).toFixed(0) : null,
      unit: "/ 100",
      level: kpis?.overallRiskLevel ?? null,
      // Says what the average is over. A city figure computed from three of
      // twenty zones is a different claim from one over all twenty.
      note: kpis ? `${kpis.zonesReporting} of ${kpis.zonesMonitored} zones reporting` : undefined,
    },
  ];

  return (
    <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-2 lg:grid-cols-4">
      {tiles.map((tile) => (
        <div key={tile.label} className="bg-surface-raised px-4 py-3.5">
          <div className="text-[12px] text-content-tertiary">{tile.label}</div>

          {loading ? (
            <Skeleton className="mt-1.5 h-6 w-20" />
          ) : tile.value === null ? (
            // Explicit, not a dash: the reader should know the platform did not
            // measure this, not wonder whether the number failed to render.
            <div className="mt-1 text-[13px] text-content-disabled">Not measured</div>
          ) : (
            <div className="mt-1 flex items-baseline gap-1.5">
              <span
                className={`text-xl font-semibold tabular tracking-tight ${
                  tile.level ? LEVEL_TEXT[tile.level] : ""
                }`}
              >
                {tile.value}
              </span>
              {tile.unit && <span className="text-[12px] text-content-tertiary">{tile.unit}</span>}
            </div>
          )}

          {tile.note && !loading && (
            <div className="mt-0.5 text-[11px] text-content-tertiary">{tile.note}</div>
          )}
        </div>
      ))}
    </div>
  );
}
