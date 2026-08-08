"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import {
  Badge,
  Card,
  CardHeader,
  DemoDataBadge,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHeader,
} from "@/components/ui";
import { forecastApi, geoApi, liveApi } from "@/lib/api/endpoints";
import type {
  ConditionLevel,
  ForecastMetric,
  ForecastPoint,
  ModelSummary,
  ZoneHistoryPoint,
} from "@/lib/api/types";
import { ForecastChart } from "@/components/charts/ForecastChart";
import { useSelectedCity } from "@/lib/city-context";

/**
 * Forecast Engine (PRD §11).
 *
 * The organising idea: a prediction is shown with the evidence needed to judge
 * it, never alone. Each horizon carries its interval, the confidence derived
 * from that horizon's measured error, the error itself next to the naive
 * baseline it beat, and the factors that drove it. PRD §15 forbids asking a user
 * to take a number on trust, and a forecast is the easiest number in the product
 * to take on trust.
 */

const METRICS: Array<{
  value: ForecastMetric;
  label: string;
  unit: string;
  decimals: number;
  /** Pulls the matching observation out of a history point, so the chart plots
      the same quantity the forecast predicts. Null where history does not carry
      it — vehicle count is not stored per window. */
  readHistory: (p: ZoneHistoryPoint) => number | null;
  /** Display multiplier. Ratios are stored 0-1 and read as percentages. */
  scale: number;
}> = [
  {
    value: "occupancy_ratio", label: "Congestion", unit: "of capacity", decimals: 2,
    readHistory: (p) => (p.occupancyRatio === null ? null : Number(p.occupancyRatio)),
    scale: 1,
  },
  {
    value: "average_speed_kph", label: "Average speed", unit: "km/h", decimals: 1,
    readHistory: (p) => (p.averageSpeedKph === null ? null : Number(p.averageSpeedKph)),
    scale: 1,
  },
  {
    value: "vehicle_count", label: "Vehicle volume", unit: "vehicles", decimals: 0,
    // Curated windows do not store a vehicle count, so this metric charts its
    // forecast without an observed line rather than plotting a stand-in.
    readHistory: () => null,
    scale: 1,
  },
  {
    value: "risk_score", label: "Composite risk", unit: "/ 100", decimals: 0,
    readHistory: (p) => (p.riskScore === null ? null : Number(p.riskScore)),
    scale: 1,
  },
];

const HORIZON_LABELS: Record<number, string> = {
  15: "15 min",
  30: "30 min",
  60: "1 hour",
  180: "3 hours",
  360: "6 hours",
};

const LEVEL_BADGE: Record<ConditionLevel, "normal" | "moderate" | "high" | "critical"> = {
  NORMAL: "normal",
  MODERATE: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
};

function format(value: string | null, decimals: number): string {
  if (value === null) return "—";
  return Number(value).toFixed(decimals);
}

export default function ForecastPage() {
  const { city } = useSelectedCity();
  const [metric, setMetric] = useState<ForecastMetric>("occupancy_ratio");
  const [zoneId, setZoneId] = useState<string | null>(null);

  const zonesQuery = useQuery({
    queryKey: ["zones", city?.id],
    queryFn: () => geoApi.listZones(city!.id, true),
    enabled: Boolean(city),
  });

  const zones = zonesQuery.data ?? [];
  const selectedZoneId = zoneId ?? zones[0]?.id ?? null;

  const forecastQuery = useQuery({
    queryKey: ["forecast", selectedZoneId, metric],
    queryFn: () => forecastApi.forZone(selectedZoneId!, metric),
    enabled: Boolean(selectedZoneId),
  });

  // The observed line the forecast continues from. Kept separate from the
  // forecast query so a missing history degrades the chart to predictions alone
  // rather than failing the page.
  const historyQuery = useQuery({
    queryKey: ["forecast-history", selectedZoneId],
    queryFn: () => liveApi.history(selectedZoneId!),
    enabled: Boolean(selectedZoneId),
    staleTime: 60_000,
  });

  const accuracyQuery = useQuery({
    queryKey: ["forecast-accuracy"],
    queryFn: () => forecastApi.accuracy(),
  });

  const config = METRICS.find((m) => m.value === metric)!;

  if (!city) {
    return <LoadingState label="Loading city" rows={4} />;
  }

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Forecast Engine"
        subtitle={`${city.name} · predictions with the error that produced their confidence`}
        actions={forecastQuery.data?.demoData ? <DemoDataBadge /> : undefined}
      />

      <div className="flex flex-wrap gap-3">
        <label className="flex items-center gap-2 text-[13px]">
          <span className="text-content-tertiary">Zone</span>
          <select
            value={selectedZoneId ?? ""}
            onChange={(event) => setZoneId(event.target.value)}
            className="rounded-md border border-line-subtle bg-surface-raised px-2.5 py-1.5 text-[13px]"
          >
            {zones.map((zone) => (
              <option key={zone.id} value={zone.id}>
                {zone.name} ({zone.code})
              </option>
            ))}
          </select>
        </label>

        <label className="flex items-center gap-2 text-[13px]">
          <span className="text-content-tertiary">Metric</span>
          <select
            value={metric}
            onChange={(event) => setMetric(event.target.value as ForecastMetric)}
            className="rounded-md border border-line-subtle bg-surface-raised px-2.5 py-1.5 text-[13px]"
          >
            {METRICS.map((m) => (
              <option key={m.value} value={m.value}>
                {m.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      {forecastQuery.isError && (
        <ErrorState
          title="Forecasts unavailable"
          message={
            forecastQuery.error instanceof Error
              ? forecastQuery.error.message
              : "Could not load predictions."
          }
          onRetry={() => void forecastQuery.refetch()}
        />
      )}

      <Card className="overflow-hidden">
        <CardHeader
          title={`${config.label} outlook`}
          description="Each horizon is a separately trained model with its own measured error."
          action={
            forecastQuery.data?.currentValue != null ? (
              <span className="text-[12px] text-content-tertiary">
                now {format(forecastQuery.data.currentValue, config.decimals)} {config.unit}
              </span>
            ) : forecastQuery.data ? (
              // Not a rendering failure: no recent observation exists, and
              // showing a stale "current" would misrepresent the starting point
              // the predictions extend from.
              <span className="text-[12px] text-content-disabled">no recent observation</span>
            ) : undefined
          }
        />

        {forecastQuery.isLoading ? (
          <LoadingState label="Loading forecasts" rows={5} />
        ) : !forecastQuery.data?.horizons.length ? (
          <EmptyState
            title="No forecasts yet"
            description="This zone is monitored but has no stored predictions. Run the prediction job: python -m ml.predict"
          />
        ) : (
          <>
            {/* The chart first, the table under it. A table can be read but not
                seen: whether the prediction continues the trend or breaks it,
                and whether the interval is narrow enough for the number to mean
                anything, are shape questions. The table keeps the exact values
                for anyone who needs them. */}
            <ForecastChart
              history={historyQuery.data?.points ?? []}
              horizons={forecastQuery.data.horizons}
              readHistory={config.readHistory}
              scale={config.scale}
              unit={config.unit}
              decimals={config.decimals}
            />
            <HorizonTable points={forecastQuery.data.horizons} decimals={config.decimals} unit={config.unit} />
          </>
        )}
      </Card>

      {forecastQuery.data?.horizons.length ? (
        <FactorPanel points={forecastQuery.data.horizons} />
      ) : null}

      <div className="grid gap-5 lg:grid-cols-2">
        {forecastQuery.data?.model && <ModelPanel model={forecastQuery.data.model} />}
        <AccuracyPanel
          entries={accuracyQuery.data?.entries.filter((e) => e.targetMetric === metric) ?? []}
          loading={accuracyQuery.isLoading}
        />
      </div>
    </div>
  );
}

function HorizonTable({
  points,
  decimals,
  unit,
}: {
  points: ForecastPoint[];
  decimals: number;
  unit: string;
}) {
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-[13px]">
        <thead>
          <tr className="border-b border-line-subtle text-[12px] text-content-tertiary">
            <th scope="col" className="px-5 py-2.5 font-medium">Horizon</th>
            <th scope="col" className="px-5 py-2.5 font-medium">At</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">Predicted</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">95% interval</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">Confidence</th>
            <th scope="col" className="px-5 py-2.5 font-medium">Level</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">Measured error</th>
          </tr>
        </thead>
        <tbody>
          {points.map((point) => (
            <tr key={point.id} className="border-b border-line-subtle last:border-0">
              <td className="px-5 py-2.5 font-medium">
                {HORIZON_LABELS[point.horizonMinutes] ?? `${point.horizonMinutes} min`}
              </td>
              <td className="px-5 py-2.5 text-content-tertiary">
                {new Date(point.targetTime).toLocaleTimeString([], {
                  hour: "2-digit",
                  minute: "2-digit",
                })}
              </td>
              <td className="px-5 py-2.5 text-right tabular font-medium">
                {format(point.predictedValue, decimals)}
                <span className="ml-1 text-[11px] font-normal text-content-tertiary">{unit}</span>
              </td>
              <td className="px-5 py-2.5 text-right tabular text-content-secondary">
                {format(point.lowerBound, decimals)} – {format(point.upperBound, decimals)}
              </td>
              <td className="px-5 py-2.5 text-right tabular">
                {(Number(point.confidence) * 100).toFixed(0)}%
              </td>
              <td className="px-5 py-2.5">
                {point.riskLevel ? (
                  <Badge level={LEVEL_BADGE[point.riskLevel]}>{point.riskLevel}</Badge>
                ) : (
                  // Speed and volume have no severity scale of their own — a
                  // number is not good or bad without a capacity to compare it
                  // against, and inventing a band would assert a judgement the
                  // platform has not made.
                  <span className="text-[12px] text-content-disabled">—</span>
                )}
              </td>
              <td className="px-5 py-2.5 text-right tabular text-content-secondary">
                {point.measuredMae ? (
                  <>
                    {format(point.measuredMae, decimals === 0 ? 0 : decimals + 2)}
                    {point.improvementOverBaseline && (
                      <span className="ml-1.5 text-[11px] text-status-normal">
                        {Number(point.improvementOverBaseline) > 0 ? "+" : ""}
                        {Number(point.improvementOverBaseline).toFixed(0)}% vs naive
                      </span>
                    )}
                  </>
                ) : (
                  "—"
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="border-t border-line-subtle px-5 py-3 text-[11px] leading-relaxed text-content-tertiary">
        Confidence is computed from the measured error beside it, not asserted. &ldquo;vs
        naive&rdquo; compares against predicting no change at all — a model that cannot beat that
        has not earned its complexity.
      </p>
    </div>
  );
}

function FactorPanel({ points }: { points: ForecastPoint[] }) {
  const [horizon, setHorizon] = useState<number>(points[0]?.horizonMinutes ?? 60);
  const point = points.find((p) => p.horizonMinutes === horizon) ?? points[0];

  return (
    <Card>
      <CardHeader
        title="Why this prediction"
        description="The features that moved this forecast furthest from the average, largest first."
        action={
          <select
            value={horizon}
            onChange={(event) => setHorizon(Number(event.target.value))}
            className="rounded-md border border-line-subtle bg-surface-raised px-2 py-1 text-[12px]"
          >
            {points.map((p) => (
              <option key={p.id} value={p.horizonMinutes}>
                {HORIZON_LABELS[p.horizonMinutes] ?? `${p.horizonMinutes} min`}
              </option>
            ))}
          </select>
        }
      />
      {point.contributingFactors.length === 0 ? (
        <EmptyState title="No explanation stored" description="This forecast has no recorded factors." />
      ) : (
        <ul className="divide-y divide-line-subtle">
          {point.contributingFactors.map((factor) => (
            <li key={factor.feature} className="flex items-center justify-between gap-4 px-5 py-2.5">
              <div>
                <div className="text-[13px] text-content-primary">{factor.factor}</div>
                <div className="text-[11px] text-content-tertiary">
                  {factor.feature} = {Number(factor.value).toFixed(3)}
                </div>
              </div>
              <span
                className={`text-[12px] tabular ${
                  factor.direction === "increases" ? "text-status-high" : "text-status-normal"
                }`}
              >
                {factor.direction === "increases" ? "↑" : "↓"} {Math.abs(Number(factor.effect)).toFixed(3)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function ModelPanel({ model }: { model: ModelSummary }) {
  const rows: Array<[string, string]> = [
    ["Model", `${model.name} ${model.version}`],
    ["Algorithm", model.algorithm],
    [
      "Trained on",
      `${new Date(model.trainedFrom).toLocaleDateString()} – ${new Date(model.trainedTo).toLocaleDateString()}`,
    ],
    [
      "Evaluated on",
      `${new Date(model.evaluatedFrom).toLocaleDateString()} – ${new Date(model.evaluatedTo).toLocaleDateString()}`,
    ],
    ["Training rows", model.trainingRows.toLocaleString()],
    ["Holdout rows", model.evaluationRows.toLocaleString()],
  ];

  return (
    <Card>
      <CardHeader title="Model" description="The evaluation period starts where training ends." />
      <dl className="divide-y divide-line-subtle">
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-center justify-between gap-4 px-5 py-2.5">
            <dt className="text-[13px] text-content-tertiary">{label}</dt>
            <dd className="text-[13px] tabular text-content-primary">{value}</dd>
          </div>
        ))}
      </dl>
    </Card>
  );
}

function AccuracyPanel({
  entries,
  loading,
}: {
  entries: Array<{
    horizonMinutes: number;
    scoredCount: number;
    productionMae: string;
    holdoutMae: string;
    withinIntervalPct: string;
  }>;
  loading: boolean;
}) {
  return (
    <Card className="overflow-hidden">
      <CardHeader
        title="Accuracy against reality"
        description="Production error beside the error measured on the holdout. A widening gap means the model has gone stale."
      />
      {loading ? (
        <LoadingState label="Loading accuracy" rows={3} />
      ) : entries.length === 0 ? (
        <EmptyState
          title="Nothing scored yet"
          description="Forecasts are scored once their target time has passed. Run: python -m ml.score"
        />
      ) : (
        <table className="w-full text-left text-[13px]">
          <thead>
            <tr className="border-b border-line-subtle text-[12px] text-content-tertiary">
              <th scope="col" className="px-5 py-2.5 font-medium">Horizon</th>
              <th scope="col" className="px-5 py-2.5 text-right font-medium">Production</th>
              <th scope="col" className="px-5 py-2.5 text-right font-medium">Holdout</th>
              <th scope="col" className="px-5 py-2.5 text-right font-medium">In interval</th>
            </tr>
          </thead>
          <tbody>
            {entries.map((entry) => (
              <tr key={entry.horizonMinutes} className="border-b border-line-subtle last:border-0">
                <td className="px-5 py-2.5">
                  {HORIZON_LABELS[entry.horizonMinutes] ?? `${entry.horizonMinutes} min`}
                  <span className="ml-1.5 text-[11px] text-content-tertiary">
                    n={entry.scoredCount.toLocaleString()}
                  </span>
                </td>
                <td className="px-5 py-2.5 text-right tabular">{Number(entry.productionMae).toFixed(4)}</td>
                <td className="px-5 py-2.5 text-right tabular text-content-secondary">
                  {Number(entry.holdoutMae).toFixed(4)}
                </td>
                <td className="px-5 py-2.5 text-right tabular">
                  {Number(entry.withinIntervalPct).toFixed(0)}%
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Card>
  );
}
