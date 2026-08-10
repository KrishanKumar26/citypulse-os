"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { Badge, Card, CardHeader, Skeleton, cn } from "@/components/ui";
import { alertApi, forecastApi, intelligenceApi } from "@/lib/api/endpoints";
import type {
  AlertDetail,
  AnomalyDetail,
  CityOutlook,
  City,
  ZoneCondition,
  ZoneOutlook,
} from "@/lib/api/types";
import { Glyph } from "@/components/ui/icons";
import { describeSituation } from "@/lib/situation-language";
import { TechnicalDetails, type TechnicalRow } from "./TechnicalDetails";

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
 * deviation is a multiple of the metric's usual spread, so it is comparable
 * across metrics in a way that
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

/** Display multiplier — ratios are stored 0-1 and read as percentages. */
const METRIC_SCALE: Record<string, number> = { occupancy_ratio: 100 };
const METRIC_UNIT: Record<string, string> = {
  occupancy_ratio: "% of capacity",
  average_speed_kph: "km/h",
  vehicle_count: "vehicles",
  risk_score: "/ 100",
};

/**
 * The zone's forecast, but only when it forecasts what the anomaly measured.
 *
 * The city outlook covers exactly one metric — whichever the model was trained
 * for — and every zone in it carries a value for that metric alone. Shown
 * beside an anomaly on a different measure, an occupancy ratio of 0.61 rendered
 * as "0.6 / 100" under a risk row: the platform appearing to predict that a
 * zone at 30 of 100 was about to collapse to nearly zero, in every row, on the
 * first screen after sign-in. Two quantities, one wearing the other's unit.
 *
 * Exported so the rule can be tested without a render.
 */
export function outlookFor(
  anomaly: Pick<AnomalyDetail, "metric" | "zoneId">,
  outlook: Pick<CityOutlook, "targetMetric"> | undefined,
  byZone: Map<string, ZoneOutlook>,
): ZoneOutlook | undefined {
  if (!outlook || outlook.targetMetric !== anomaly.metric) return undefined;
  return byZone.get(anomaly.zoneId);
}


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

  // The city outlook forecasts one metric — whichever the model was trained
  // for — and every zone in it carries a value for that metric alone. Pairing
  // it with an anomaly on a different measure put an occupancy ratio of 0.61
  // under a risk row and rendered it "0.6 / 100", which read as the platform
  // predicting that a zone at 30 of 100 was about to fall to nearly zero. Two
  // different quantities, one of them wearing the other's unit.
  // Pairing is done by outlookFor below, which is exported and tested apart
  // from any render: the failure it prevents is a well-formed, correctly
  // coloured number about the wrong quantity, which no rendering test would
  // catch by looking at the markup.

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
      // Only when the forecast is of the same thing the anomaly measured.
      outlook: outlookFor(anomaly, outlookQuery.data, outlookByZone),
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
              ? "Nothing has behaved unlike itself in the last six hours."
              : `${situations.length} ${situations.length === 1 ? "situation" : "situations"}` +
                (critical > 0 ? ` · ${critical} critical` : "") +
                " · most serious first"
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
  const raw = Number(anomaly.observedValue);
  const rawBaseline = Number(anomaly.baselineValue);
  const copy = describeSituation(anomaly.metric, raw, rawBaseline);
  const anomalyRatio = rawBaseline === 0 ? null : raw / rawBaseline;

  // The forecast's own move against what it was issued from — not against the
  // anomaly's observation, which is a different window.
  const predicted = outlook ? Number(outlook.predictedValue) * scale : null;

  const technicalRows: TechnicalRow[] = [
    { label: copy.technicalLabel, value: (raw * scale).toFixed(scale === 100 ? 0 : 2) },
    { label: "Historical baseline", value: (rawBaseline * scale).toFixed(scale === 100 ? 0 : 2) },
    {
      label: "Anomaly ratio",
      value: anomalyRatio === null ? "No baseline" : `${anomalyRatio.toFixed(1)}x`,
    },
    { label: "Historical readings", value: String(anomaly.baselineSamples) },
    { label: "Observed at", value: new Date(anomaly.detectedAt).toLocaleTimeString() },
    {
      label: "Historical spread",
      value: `${Number(anomaly.deviationScore).toFixed(1)}x`,
    },
    {
      label: "Forecast",
      value:
        predicted === null
          ? "Not available"
          : `${predicted.toFixed(scale === 100 ? 0 : 1)} ${unit}`.trim(),
    },
    // The sentence the pipeline stored. It is the detector's own words and the
    // only row here a reader can quote back when questioning a detection.
    { label: "Detector note", value: anomaly.explanation },
  ];

  // A rule's recommendation is a different kind of claim from generic advice
  // about this kind of situation, and the card must not let them read alike:
  // one was computed for this zone, the other is true of congestion anywhere.
  const ruleAction = alert?.recommendedAction ?? null;

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
            <span className={cn("flex items-center gap-1.5", `text-status-${status}`)}>
              <Glyph name={copy.icon} size={15} />
              <h3 className="text-[14px] font-semibold tracking-tight">{copy.title}</h3>
            </span>
            <span className="text-[13px] text-content-secondary">{anomaly.zoneName}</span>
          </div>

          {/* One sentence, aimed at whoever is deciding whether to take another
              route. Everything a duty officer needs to judge the detection is
              still here, in the panel at the foot of the card. */}
          <p className="mt-2 text-[14px] leading-snug text-content-primary">{copy.headline}</p>

          <p className="mt-2 text-[13px] text-content-secondary">
            <span className="tabular font-medium text-content-primary">{copy.reading}</span>
          </p>
          <p className="text-[12px] text-content-tertiary">{copy.usual}</p>
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

      <dl className="mt-3 grid gap-x-6 gap-y-2 sm:grid-cols-2">
        <Fact label="What this means">{copy.meaning}</Fact>

        <Fact label="Recommended action">
          {ruleAction ?? (
            <>
              {copy.guidance}{" "}
              <span
                className="text-content-disabled"
                title="No alert rule has fired for this zone, so this is general guidance for this kind of situation rather than a recommendation computed for here."
              >
                · general guidance
              </span>
            </>
          )}
        </Fact>
      </dl>

      <TechnicalDetails rows={technicalRows} />
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
