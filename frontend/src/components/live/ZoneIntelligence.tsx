"use client";

import { useQuery } from "@tanstack/react-query";
import { Badge, Card, CardHeader, EmptyState, Metric, Skeleton, cn } from "@/components/ui";
import { Sparkline, TrendBadge, trendOf } from "@/components/charts/Sparkline";
import { alertApi, forecastApi, liveApi } from "@/lib/api/endpoints";
import type { ConditionLevel, Zone, ZoneCondition } from "@/lib/api/types";

/**
 * What is happening in one zone, why, what is expected next, and what to do.
 *
 * The panel this replaces listed the zone's current readings and stopped. Every
 * question an operator actually has starts after that: is this worse than usual,
 * is it still climbing, and is anyone supposed to act.
 *
 * Every section here is a stored row or a model output. There is deliberately no
 * generated advice: the recommended action shown is the one an alert rule
 * attached when it fired, and the reasons shown are the factors the forecast
 * model actually weighted. When neither exists the panel says so. A plausible
 * sentence assembled by the interface would be indistinguishable from a real
 * recommendation, and acting on an invented one is the failure this product
 * exists to avoid (PRD §42).
 */

const LEVEL_TO_STATUS = {
  NORMAL: "normal",
  MODERATE: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
} as const;

const LEVEL_WORD: Record<ConditionLevel, string> = {
  NORMAL: "Normal",
  MODERATE: "Elevated",
  HIGH: "High",
  CRITICAL: "Critical",
};

export function ZoneIntelligence({
  zone,
  condition,
  cityId,
}: {
  zone: Zone | null;
  condition: ZoneCondition | undefined;
  cityId: string | undefined;
}) {
  const historyQuery = useQuery({
    queryKey: ["zone-history", zone?.id],
    queryFn: () => liveApi.history(zone!.id),
    enabled: Boolean(zone),
    staleTime: 60_000,
  });

  const forecastQuery = useQuery({
    queryKey: ["zone-forecast", zone?.id],
    queryFn: () => forecastApi.forZone(zone!.id),
    enabled: Boolean(zone),
    staleTime: 60_000,
  });

  const alertsQuery = useQuery({
    queryKey: ["zone-alerts", cityId],
    queryFn: () => alertApi.list({ cityId, openOnly: true, size: 50 }),
    enabled: Boolean(cityId),
    staleTime: 30_000,
  });

  if (!zone) {
    return (
      <Card>
        <CardHeader title="Zone intelligence" />
        <EmptyState
          title="No zone selected"
          description="Choose a zone on the map or in the ranking to see its conditions, what is driving them, and what is expected next."
        />
      </Card>
    );
  }

  const level = condition?.riskLevel ?? null;
  const points = historyQuery.data?.points ?? [];
  const occupancy = points.map((p) => (p.occupancyRatio === null ? null : Number(p.occupancyRatio) * 100));
  const risk = points.map((p) => (p.riskScore === null ? null : Number(p.riskScore)));

  // The alert list is city-wide; only this zone's open alerts are relevant, and
  // the most severe of them is the one carrying the action worth showing.
  const zoneAlerts = (alertsQuery.data?.items ?? []).filter((a) => a.zoneId === zone.id);
  const action = zoneAlerts.find((a) => a.recommendedAction)?.recommendedAction ?? null;

  const horizon = forecastQuery.data?.horizons?.find((h) => h.horizonMinutes === 60)
    ?? forecastQuery.data?.horizons?.[0]
    ?? null;
  const currentValue = forecastQuery.data?.currentValue;
  const predictedChange =
    horizon && currentValue != null && Number(currentValue) !== 0
      ? ((Number(horizon.predictedValue) - Number(currentValue)) / Math.abs(Number(currentValue))) * 100
      : null;

  return (
    <Card className="overflow-hidden">
      <CardHeader
        title={zone.name}
        description={zone.code}
        action={
          level ? (
            <Badge level={LEVEL_TO_STATUS[level]}>{LEVEL_WORD[level]}</Badge>
          ) : (
            <Badge level="neutral">No data</Badge>
          )
        }
      />

      {/* ---------- What is happening ---------- */}
      <div className="grid grid-cols-2 gap-4 border-b border-line-subtle px-5 py-4">
        <div>
          <Metric
            label="Risk"
            value={condition?.riskScore != null ? Number(condition.riskScore).toFixed(0) : null}
            unit="/ 100"
            level={level ? LEVEL_TO_STATUS[level] : null}
            emphasis="hero"
          />
          <div className="mt-2 flex items-center gap-2">
            <Sparkline points={risk} ariaLabel="Risk over the recent window" />
            <TrendBadge change={trendOf(risk)} />
          </div>
        </div>
        <div className="space-y-3">
          <div>
            <Metric
              label="Congestion"
              value={condition?.occupancyRatio != null ? (Number(condition.occupancyRatio) * 100).toFixed(0) : null}
              unit="% of capacity"
            />
            <div className="mt-1.5 flex items-center gap-2">
              <Sparkline points={occupancy} width={64} height={20} ariaLabel="Congestion over the recent window" />
              <TrendBadge change={trendOf(occupancy)} />
            </div>
          </div>
          <Metric
            label="Average speed"
            value={condition?.averageSpeedKph != null ? Number(condition.averageSpeedKph).toFixed(1) : null}
            unit="km/h"
          />
          <Metric
            label="Active incidents"
            value={condition ? String(condition.activeIncidents) : null}
          />
        </div>
      </div>

      {/* ---------- What is expected next ---------- */}
      <section className="border-b border-line-subtle px-5 py-4">
        <h4 className="mb-2.5 flex items-center gap-1.5 text-[10px] font-medium uppercase tracking-[0.1em] text-content-disabled">
          <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-ai" />
          Expected next
        </h4>

        {forecastQuery.isLoading ? (
          <Skeleton className="h-12 w-full" />
        ) : !horizon ? (
          <p className="text-[12px] text-content-tertiary">
            No forecast has been issued for this zone. The model skips a zone whose recent
            history is too thin to predict from.
          </p>
        ) : (
          <>
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="tabular text-[22px] font-semibold leading-none text-ai">
                {(Number(horizon.predictedValue) * 100).toFixed(0)}
                <span className="ml-1 text-[12px] font-normal text-content-tertiary">% of capacity</span>
              </span>
              {predictedChange !== null && (
                <TrendBadge change={predictedChange} />
              )}
              <span className="text-[11px] text-content-tertiary">
                in {horizon.horizonMinutes} min
              </span>
            </div>

            <p className="mt-1.5 text-[11px] text-content-tertiary">
              {/* Confidence is derived from the model's measured error at this
                  metric and horizon, not asserted. Saying so is the difference
                  between a number and a claim. */}
              {(Number(horizon.confidence) * 100).toFixed(0)}% confidence, from measured error on
              held-out data
            </p>

            {horizon.contributingFactors.length > 0 && (
              <div className="mt-3">
                <p className="mb-1.5 text-[10px] font-medium uppercase tracking-[0.1em] text-content-disabled">
                  Why
                </p>
                <ul className="space-y-1">
                  {horizon.contributingFactors.slice(0, 3).map((factor) => (
                    <li
                      key={factor.feature}
                      className="flex items-start gap-2 text-[11px] text-content-secondary"
                    >
                      <span aria-hidden="true" className="mt-1.5 h-1 w-1 shrink-0 rounded-full bg-ai" />
                      <span>
                        {factor.factor}
                        <span className="text-content-tertiary">
                          {" "}
                          ({factor.value}) {factor.direction} it — {factor.effect}
                        </span>
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </>
        )}
      </section>

      {/* ---------- What to do ---------- */}
      <section className="px-5 py-4">
        <h4 className="mb-2 text-[10px] font-medium uppercase tracking-[0.1em] text-content-disabled">
          Recommended action
        </h4>
        {action ? (
          <div
            className={cn(
              "rounded-md border border-line-default bg-surface-inset px-3 py-2.5 text-[12px] leading-relaxed text-content-secondary",
            )}
          >
            {action}
          </div>
        ) : (
          // Explicit rather than blank. The interface does not compose advice,
          // so "nothing to do" and "nobody has said" must not look the same.
          <p className="text-[12px] text-content-tertiary">
            No open alert for this zone carries a recommended action.
          </p>
        )}
      </section>
    </Card>
  );
}
