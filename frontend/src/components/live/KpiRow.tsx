"use client";

import { Badge, Metric, Skeleton } from "@/components/ui";
import type { CityKpis, ConditionLevel } from "@/lib/api/types";
import { formatNumber } from "@/lib/format";

/**
 * The KPI tiles from PRD §8.
 *
 * Every tile can render three states, and the third is the one that matters:
 * a measured value, a loading skeleton, and *not measured*. A dashboard that
 * shows "0 km/h" when no traffic reading exists is not merely imprecise — it
 * reports a dead feed as gridlock, which is the opposite of the truth.
 *
 * Composite risk is separated out and given real weight. Eight equal tiles made
 * the number the page exists to communicate look exactly like vehicle count,
 * and a reader scanning the row had nothing telling them where to start. It is
 * also the only figure derived from all the others, so it belongs above them
 * rather than beside them.
 *
 * The rest are grouped by what they describe — the road, the air, and what
 * operators are currently dealing with. Ungrouped, congestion sat next to air
 * quality and implied a relationship the platform does not claim.
 */

const RISK_WORD: Record<ConditionLevel, string> = {
  NORMAL: "Normal",
  MODERATE: "Elevated",
  HIGH: "High",
  CRITICAL: "Critical",
};

const LEVEL_TO_STATUS = {
  NORMAL: "normal",
  MODERATE: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
} as const;

function percent(ratio: string | null): string | null {
  if (ratio === null) return null;
  return (Number(ratio) * 100).toFixed(0);
}

export function KpiRow({ kpis, loading }: { kpis: CityKpis | null; loading: boolean }) {
  const risk = kpis?.overallRiskLevel ?? null;
  const riskStatus = risk ? LEVEL_TO_STATUS[risk] : null;

  // Coverage qualifies every figure on this row, so it is stated once against
  // the composite rather than repeated on each tile. A city average over three
  // of twenty zones is a different claim from one over all twenty.
  const coverage = kpis ? `${kpis.zonesReporting} of ${kpis.zonesMonitored} zones reporting` : undefined;

  const groups: { heading: string; metrics: Parameters<typeof Metric>[0][] }[] = [
    {
      heading: "Road",
      metrics: [
        {
          label: "Congestion",
          value: percent(kpis?.averageCongestion ?? null),
          unit: "% of capacity",
          level: riskStatus,
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
      ],
    },
    {
      heading: "Environment",
      metrics: [
        {
          label: "Air quality",
          value: kpis?.averageAqi != null ? String(kpis.averageAqi) : null,
          unit: "AQI",
          // Weather and air quality arrive on a slower feed than traffic, so a
          // five-minute window usually holds no reading. "Not measured" would
          // claim the platform never saw one, which is false and reads as a
          // fault; the truth is that this window has none yet.
          absenceReason: "No reading this window",
        },
        {
          label: "Weather",
          value: kpis?.temperatureC ? Number(kpis.temperatureC).toFixed(1) : null,
          unit: "°C",
          note: kpis?.weatherCondition?.replace(/_/g, " ").toLowerCase(),
          absenceReason: "No reading this window",
        },
      ],
    },
    {
      heading: "Operations",
      metrics: [
        {
          label: "Active incidents",
          value: kpis ? String(kpis.activeIncidents) : null,
          level: kpis && kpis.activeIncidents > 0 ? "moderate" : "neutral",
        },
        {
          label: "Open alerts",
          value: kpis ? String(kpis.activeAlerts) : null,
          level: kpis && kpis.activeAlerts > 0 ? "high" : "normal",
        },
      ],
    },
  ];

  return (
    <div className="grid gap-3 lg:grid-cols-[minmax(0,17rem)_minmax(0,1fr)]">
      {/* Composite risk, given the weight of a headline rather than a tile. */}
      <div className="flex flex-col justify-between rounded-lg border border-line-subtle bg-surface-raised px-5 py-4">
        {loading ? (
          <Skeleton className="h-14 w-32" />
        ) : (
          <>
            <Metric
              label="Composite risk"
              value={kpis?.averageRiskScore ? Number(kpis.averageRiskScore).toFixed(0) : null}
              unit="/ 100"
              level={riskStatus}
              emphasis="hero"
              note={coverage}
            />
            {risk && (
              <div className="mt-3">
                <Badge level={LEVEL_TO_STATUS[risk]}>{RISK_WORD[risk]}</Badge>
              </div>
            )}
          </>
        )}
      </div>

      <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-2 xl:grid-cols-3">
        {groups.map((group) => (
          <section key={group.heading} className="bg-surface-raised px-5 py-4">
            <h3 className="mb-3 text-[10px] font-medium uppercase tracking-[0.1em] text-content-disabled">
              {group.heading}
            </h3>
            <div className="grid gap-4">
              {group.metrics.map((metric) =>
                loading ? (
                  <Skeleton key={metric.label} className="h-9 w-24" />
                ) : (
                  <Metric key={metric.label} {...metric} />
                ),
              )}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
