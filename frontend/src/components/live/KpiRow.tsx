"use client";

import { Metric, Skeleton, cn } from "@/components/ui";
import { CityHealthRing } from "@/components/charts/CityHealthRing";
import { Sparkline, TrendBadge, trendOf } from "@/components/charts/Sparkline";
import type { CityHistory, CityKpis } from "@/lib/api/types";
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

// Ground and edge per severity. The tints are the existing status-*-bg tokens,
// so this card cannot drift away from the badge, map marker and alert row that
// describe the same level.
const RISK_CARD = {
  normal: "border-status-normal/25 bg-status-normal-bg",
  moderate: "border-status-moderate/25 bg-status-moderate-bg",
  high: "border-status-high/25 bg-status-high-bg",
  critical: "border-status-critical/30 bg-status-critical-bg",
} as const;

const RISK_EDGE = {
  normal: "bg-status-normal",
  moderate: "bg-status-moderate",
  high: "bg-status-high",
  critical: "bg-status-critical",
} as const;

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

export function KpiRow({
  kpis,
  history,
  loading,
}: {
  kpis: CityKpis | null;
  /** The city's recent series. Absent until it loads; the tiles render without
      their trend rather than waiting for it. */
  history?: CityHistory | null;
  loading: boolean;
}) {
  // Read once per metric rather than per tile, so a tile and its sparkline
  // cannot end up plotting different things.
  const points = history?.points ?? [];
  const series = {
    congestion: points.map((p) => (p.averageCongestion === null ? null : Number(p.averageCongestion) * 100)),
    speed: points.map((p) => (p.averageSpeedKph === null ? null : Number(p.averageSpeedKph))),
    vehicles: points.map((p) => p.totalVehicleCount),
    aqi: points.map((p) => p.averageAqi),
  };

  const risk = kpis?.overallRiskLevel ?? null;
  const riskStatus = risk ? LEVEL_TO_STATUS[risk] : null;

  type Tile = Parameters<typeof Metric>[0] & {
    /** Recent values for this metric. Omitted where a trend is not meaningful. */
    series?: (number | null)[];
    higherIsWorse?: boolean;
  };

  const groups: { heading: string; metrics: Tile[] }[] = [
    {
      heading: "Road",
      metrics: [
        {
          label: "Congestion",
          value: percent(kpis?.averageCongestion ?? null),
          unit: "% of capacity",
          level: riskStatus,
          series: series.congestion,
        },
        {
          label: "Average speed",
          value: kpis?.averageSpeedKph ? Number(kpis.averageSpeedKph).toFixed(1) : null,
          unit: "km/h",
          series: series.speed,
          // Falling speed is the bad direction, unlike every other tile here.
          higherIsWorse: false,
        },
        {
          label: "Vehicles",
          value: kpis?.totalVehicleCount != null ? formatNumber(kpis.totalVehicleCount) : null,
          unit: "in window",
          series: series.vehicles,
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
          series: series.aqi,
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
          // Not `kpis ? ... : null` — the count itself is nullable now. A city
          // whose feeds have all stopped reported "0 incidents", which reads as
          // a calm evening rather than as a blind one.
          value: kpis?.activeIncidents != null ? String(kpis.activeIncidents) : null,
          level: kpis?.activeIncidents ? "moderate" : "neutral",
          absenceReason: "No zone reporting",
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
      {/*
        Composite risk as a ring rather than a numeral.

        A bare "55 / 100" cannot be placed without knowing where the bands fall,
        so the reader has to be told the scale somewhere else or guess it. The
        arc carries the scale with the value, and the three measured inputs
        beneath answer the question the number provokes — 55 *of what* — and show
        when one signal is carrying the whole score. A city at 55 from one
        incident needs a different response from one at 55 because every road is
        full, and the single figure cannot tell those apart.
      */}
      <div
        className={cn(
          "relative overflow-hidden rounded-lg border shadow-[var(--shadow-card)] transition-colors",
          riskStatus ? RISK_CARD[riskStatus] : "border-line-subtle bg-surface-raised",
        )}
      >
        {riskStatus && (
          <span
            aria-hidden="true"
            className={cn("absolute inset-y-0 left-0 w-[3px]", RISK_EDGE[riskStatus])}
          />
        )}
        {loading ? (
          <div className="flex h-[280px] items-center justify-center">
            <Skeleton className="h-28 w-28 rounded-full" />
          </div>
        ) : (
          <CityHealthRing kpis={kpis} />
        )}
      </div>

      <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-2 xl:grid-cols-3">
        {groups.map((group) => (
          <section key={group.heading} className="bg-surface-raised px-5 py-4">
            {/* A cyan tick beside each group. The accent existed only on the
                active nav item, so nothing on the page carried the product's
                colour — every section read as the same anonymous panel. */}
            <h3 className="mb-3 flex items-center gap-2 text-[10px] font-medium uppercase tracking-[0.1em] text-content-tertiary">
              <span aria-hidden="true" className="h-3 w-[2px] rounded-full bg-accent/60" />
              {group.heading}
            </h3>
            <div className="grid gap-4">
              {group.metrics.map(({ series: trend, higherIsWorse, ...metric }) =>
                loading ? (
                  <Skeleton key={metric.label} className="h-9 w-24" />
                ) : (
                  <div key={metric.label}>
                    <Metric {...metric} />
                    {/* Only when there is something to plot. An empty
                        sparkline slot on every tile would be a row of
                        placeholders pretending to be a chart. */}
                    {trend && trend.filter((v) => v !== null).length >= 2 && (
                      <div className="mt-1.5 flex items-center gap-2">
                        <Sparkline points={trend} width={64} height={20} ariaLabel={`${metric.label} trend`} />
                        <TrendBadge change={trendOf(trend)} higherIsWorse={higherIsWorse ?? true} />
                      </div>
                    )}
                  </div>
                ),
              )}
            </div>
          </section>
        ))}
      </div>
    </div>
  );
}
