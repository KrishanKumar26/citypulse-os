"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Badge, Card, CardHeader, Skeleton, cn } from "@/components/ui";
import { alertApi, forecastApi, intelligenceApi } from "@/lib/api/endpoints";
import type {
  AlertDetail,
  AnomalyDetail,
  City,
  ZoneCondition,
  ZoneOutlook,
} from "@/lib/api/types";

/**
 * What needs attention, ranked — the first thing on the Command Center.
 *
 * The page led with twelve metric tiles, which answer "what are the numbers"
 * rather than "what should I look at". An operator arriving mid-shift had to
 * read the whole row, compare each figure against a sense of normal they were
 * expected to already have, and infer the answer.
 *
 * A situation here is assembled from rows that already exist, never composed:
 *
 *   WHAT   the anomaly's observed value against the baseline it broke
 *   WHERE  the zone it was detected in
 *   WHY    the explanation stored at detection time
 *   NEXT   the forecast issued for that zone, with its measured confidence
 *   ACTION the recommendedAction an alert rule attached when it fired
 *
 * Nothing is inferred to fill a gap. A situation with no forecast says the
 * forecast is missing; one with no open alert says no action has been
 * recommended. "Nothing to do" and "nobody has said" are different facts, and a
 * feed that smooths over the difference is the one an operator learns to
 * distrust.
 *
 * Ranked by severity first, then by how far from normal the reading was —
 * deviation is in scaled MADs, so it is comparable across metrics in a way that
 * a raw percentage is not.
 */

const SEVERITY_RANK: Record<string, number> = { CRITICAL: 0, HIGH: 1, MEDIUM: 2, LOW: 3 };

const SEVERITY_TO_STATUS = {
  CRITICAL: "critical",
  HIGH: "high",
  MEDIUM: "moderate",
  LOW: "normal",
} as const;

const SEVERITY_HEADING: Record<string, string> = {
  CRITICAL: "Critical",
  HIGH: "High",
  MEDIUM: "Developing",
  LOW: "Watch",
};

const METRIC_LABEL: Record<string, string> = {
  occupancy_ratio: "Congestion",
  average_speed_kph: "Average speed",
  vehicle_count: "Vehicle volume",
  risk_score: "Overall risk",
};

/** Display multiplier — ratios are stored 0-1 and read as percentages. */
const METRIC_SCALE: Record<string, number> = { occupancy_ratio: 100 };
const METRIC_UNIT: Record<string, string> = {
  occupancy_ratio: "% of capacity",
  average_speed_kph: "km/h",
  vehicle_count: "vehicles",
  risk_score: "/ 100",
};

interface Situation {
  anomaly: AnomalyDetail;
  outlook: ZoneOutlook | undefined;
  alert: AlertDetail | undefined;
  condition: ZoneCondition | undefined;
}

export function SituationFeed({
  city,
  conditions,
  onInvestigate,
}: {
  city: City;
  conditions: Map<string, ZoneCondition>;
  /** Selects the zone on this page's map and panel. */
  onInvestigate: (zoneId: string) => void;
}) {
  const anomaliesQuery = useQuery({
    queryKey: ["situations-anomalies", city.slug],
    queryFn: () => intelligenceApi.anomalies(city.slug, 6, 40),
    staleTime: 60_000,
  });

  const outlookQuery = useQuery({
    queryKey: ["situations-outlook", city.slug],
    queryFn: () => forecastApi.forCity(city.slug),
    staleTime: 60_000,
  });

  const alertsQuery = useQuery({
    queryKey: ["situations-alerts", city.id],
    queryFn: () => alertApi.list({ cityId: city.id, openOnly: true, size: 50 }),
    staleTime: 30_000,
  });

  const loading = anomaliesQuery.isLoading || outlookQuery.isLoading;

  const outlookByZone = new Map((outlookQuery.data?.zones ?? []).map((z) => [z.zoneId, z]));
  const alertByZone = new Map(
    (alertsQuery.data?.items ?? [])
      .filter((a) => a.zoneId && a.recommendedAction)
      .map((a) => [a.zoneId as string, a]),
  );

  // One situation per zone: the most severe anomaly it has. Listing four
  // anomalies from the same junction pushes the rest of the city off the screen
  // and reads as four problems rather than one.
  const worstPerZone = new Map<string, AnomalyDetail>();
  (anomaliesQuery.data?.items ?? []).forEach((a) => {
    const held = worstPerZone.get(a.zoneId);
    if (
      !held ||
      SEVERITY_RANK[a.severity] < SEVERITY_RANK[held.severity] ||
      (a.severity === held.severity && Number(a.deviationScore) > Number(held.deviationScore))
    ) {
      worstPerZone.set(a.zoneId, a);
    }
  });

  const situations: Situation[] = [...worstPerZone.values()]
    .sort(
      (a, b) =>
        SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity] ||
        Number(b.deviationScore) - Number(a.deviationScore),
    )
    .slice(0, 6)
    .map((anomaly) => ({
      anomaly,
      outlook: outlookByZone.get(anomaly.zoneId),
      alert: alertByZone.get(anomaly.zoneId),
      condition: conditions.get(anomaly.zoneId),
    }));

  const critical = situations.filter((s) => s.anomaly.severity === "CRITICAL").length;

  return (
    <Card className="overflow-hidden">
      <CardHeader
        title="Requiring attention"
        description={
          loading
            ? "Reading the last six hours…"
            : situations.length === 0
              ? "Nothing has departed from its baseline in the last six hours."
              : `${situations.length} ${situations.length === 1 ? "situation" : "situations"}` +
                (critical > 0 ? ` · ${critical} critical` : "") +
                " · ranked by severity, then by distance from normal"
        }
      />

      {loading ? (
        <div className="space-y-3 p-5">
          <Skeleton className="h-24" />
          <Skeleton className="h-24" />
        </div>
      ) : situations.length === 0 ? (
        <div className="px-5 py-8">
          <p className="text-[13px] text-content-secondary">
            No zone has departed from what it normally does at this hour of the week.
          </p>
          <p className="mt-1.5 text-[12px] text-content-tertiary">
            This is a statement about the last six hours of curated windows, not about the city.
            A zone whose feed has stopped cannot produce an anomaly, so check coverage before
            reading this as quiet.
          </p>
        </div>
      ) : (
        <ul className="divide-y divide-line-subtle">
          {situations.map((situation) => (
            <SituationRow
              key={situation.anomaly.id}
              situation={situation}
              onInvestigate={onInvestigate}
            />
          ))}
        </ul>
      )}
    </Card>
  );
}

function SituationRow({
  situation,
  onInvestigate,
}: {
  situation: Situation;
  onInvestigate: (zoneId: string) => void;
}) {
  const { anomaly, outlook, alert } = situation;
  const status = SEVERITY_TO_STATUS[anomaly.severity as keyof typeof SEVERITY_TO_STATUS] ?? "moderate";
  const scale = METRIC_SCALE[anomaly.metric] ?? 1;
  const unit = METRIC_UNIT[anomaly.metric] ?? "";
  const observed = Number(anomaly.observedValue) * scale;
  const baseline = Number(anomaly.baselineValue) * scale;
  const change = anomaly.percentChange === null ? null : Number(anomaly.percentChange);

  // The forecast's own move against what it was issued from — not against the
  // anomaly's observation, which is a different window.
  const predicted = outlook ? Number(outlook.predictedValue) * scale : null;

  return (
    <li className="relative px-5 py-4">
      <span
        aria-hidden="true"
        className={cn("absolute inset-y-0 left-0 w-[3px]", `bg-status-${status}`)}
      />

      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <Badge level={status}>{SEVERITY_HEADING[anomaly.severity] ?? anomaly.severity}</Badge>
            <h3 className="text-[14px] font-semibold tracking-tight text-content-primary">
              {anomaly.zoneName}
            </h3>
            <span className="text-[11px] text-content-tertiary">
              {METRIC_LABEL[anomaly.metric] ?? anomaly.metric}
            </span>
          </div>

          {/* WHAT — the reading against the baseline it broke. */}
          <p className="mt-2 flex flex-wrap items-baseline gap-x-2 text-[13px]">
            <span className={cn("tabular text-[20px] font-semibold leading-none", `text-status-${status}`)}>
              {observed.toFixed(scale === 100 ? 0 : 1)}
            </span>
            <span className="text-[11px] text-content-tertiary">{unit}</span>
            <span className="text-content-tertiary">
              against a usual {baseline.toFixed(scale === 100 ? 0 : 1)}
              {change !== null && (
                <> · {change > 0 ? "+" : ""}{change.toFixed(0)}%</>
              )}
            </span>
          </p>
        </div>

        <div className="flex shrink-0 flex-wrap items-center gap-1.5">
          {/* Only actions that reach something that exists. There is no
              response-plan feature, so no button offers to create one. */}
          <button
            type="button"
            onClick={() => onInvestigate(anomaly.zoneId)}
            className="rounded-md border border-accent/40 bg-accent-subtle px-2.5 py-1 text-[11px] font-medium text-accent transition-colors hover:bg-accent-muted"
          >
            Investigate
          </button>
          <Link
            href="/forecast"
            className="rounded-md border border-line-default px-2.5 py-1 text-[11px] text-content-secondary transition-colors hover:bg-surface-hover hover:text-content-primary"
          >
            Forecast
          </Link>
          <Link
            href="/simulator"
            className="rounded-md border border-line-default px-2.5 py-1 text-[11px] text-content-secondary transition-colors hover:bg-surface-hover hover:text-content-primary"
          >
            Simulate
          </Link>
        </div>
      </div>

      <dl className="mt-3 grid gap-x-6 gap-y-2 sm:grid-cols-3">
        {/* WHY — written at detection time, so it stays true as the code moves. */}
        <Fact label="Why">{anomaly.explanation}</Fact>

        {/* WHAT NEXT — the model's, or an explicit absence. */}
        <Fact label="Expected next">
          {outlook && predicted !== null ? (
            <>
              <span className="tabular font-medium text-ai">
                {predicted.toFixed(scale === 100 ? 0 : 1)}
              </span>{" "}
              {unit} at the next horizon
              {outlook.confidence != null && (
                <span className="text-content-tertiary">
                  {" "}· {(Number(outlook.confidence) * 100).toFixed(0)}% confidence, from measured
                  error
                </span>
              )}
            </>
          ) : (
            <span className="text-content-disabled">
              No forecast issued for this zone
            </span>
          )}
        </Fact>

        {/* WHAT TO DO — a rule's, or nothing. Never composed here. */}
        <Fact label="Recommended action">
          {alert?.recommendedAction ?? (
            <span className="text-content-disabled">No supporting signal available</span>
          )}
        </Fact>
      </dl>

      <p className="mt-2.5 text-[10px] text-content-tertiary">
        Detected {new Date(anomaly.detectedAt).toLocaleTimeString()} · baseline from{" "}
        {anomaly.baselineSamples} historical windows · {Number(anomaly.deviationScore).toFixed(1)}{" "}
        scaled MADs from normal
      </p>
    </li>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-[10px] font-medium uppercase tracking-[0.09em] text-content-disabled">
        {label}
      </dt>
      <dd className="mt-1 text-[12px] leading-relaxed text-content-secondary">{children}</dd>
    </div>
  );
}
