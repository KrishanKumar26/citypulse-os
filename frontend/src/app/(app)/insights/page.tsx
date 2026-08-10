"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";

import {
  Badge,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHeader,
  cn,
} from "@/components/ui";
import { LiftChart } from "@/components/charts/LiftChart";
import { intelligenceApi } from "@/lib/api/endpoints";
import type {
  AlertSeverity,
  AnomalyDetail,
  Correlation,
  MemoryRecall,
} from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";

/**
 * AI Insights (PRD §12, §13, §16).
 *
 * The organising constraint: this page may only say things it can point at data
 * for. Every anomaly shows the baseline it departed from and how many historical
 * windows that baseline rests on; every correlation shows the counts behind it
 * and is labelled as co-occurrence rather than cause; and City Memory either
 * cites enough past situations or says plainly that it cannot.
 *
 * That last case is not an error state — "we have not seen this before" is a
 * real and useful answer, and dressing it up as a confident median over three
 * examples would be the failure.
 */

const SEVERITY_BADGE: Record<AlertSeverity, "normal" | "moderate" | "high" | "critical"> = {
  LOW: "normal",
  MEDIUM: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
};

const METRIC_LABELS: Record<string, string> = {
  occupancy_ratio: "Road occupancy",
  average_speed_kph: "Average speed",
  vehicle_count: "Vehicle volume",
  risk_score: "Overall risk",
  aqi: "Air quality",
};

const RAIN_BANDS = ["NONE", "LIGHT", "MODERATE", "HEAVY"];
const HOUR_BANDS = ["OVERNIGHT", "MORNING_PEAK", "MIDDAY", "EVENING_PEAK", "EVENING"];
const INCIDENT_BANDS = ["NONE", "SOME", "MANY"];

function readableBand(value: string): string {
  return value.charAt(0) + value.slice(1).toLowerCase().replace(/_/g, " ");
}

export default function InsightsPage() {
  const { city } = useSelectedCity();

  const insights = useQuery({
    queryKey: ["insights", city?.slug],
    queryFn: () => intelligenceApi.insights(city!.slug),
    enabled: Boolean(city),
    // Anomalies are written by a batch job, so the page goes stale on its own.
    refetchInterval: 60_000,
  });

  if (!city) {
    return <LoadingState label="Loading city" rows={4} />;
  }

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="AI Insights"
        subtitle={<>{city.name} · questions the platform can answer, and the readings behind each answer</>}
      />

      {insights.isError && (
        <ErrorState
          title="Insights unavailable"
          message={
            insights.error instanceof Error ? insights.error.message : "Could not load insights."
          }
          onRetry={() => void insights.refetch()}
        />
      )}

      <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-3">
        <Stat
          label="Anomalies (24h)"
          value={insights.data ? String(insights.data.anomaliesLast24h) : null}
          loading={insights.isLoading}
        />
        <Stat
          label="Measured correlations"
          value={insights.data ? String(insights.data.correlations.length) : null}
          loading={insights.isLoading}
        />
        <Stat
          label="Baseline coverage"
          value={insights.data ? `${insights.data.baselineBuckets.toLocaleString()}` : null}
          note="zone × metric × hour buckets learned"
          loading={insights.isLoading}
        />
      </div>

      <CurrentSituation recall={insights.data?.currentSituation ?? null} loading={insights.isLoading} />

      <AnomaliesPanel
        anomalies={insights.data?.topAnomalies ?? []}
        loading={insights.isLoading}
      />

      <CorrelationsPanel
        correlations={insights.data?.correlations ?? []}
        loading={insights.isLoading}
      />

      <MemoryExplorer citySlug={city.slug} />
    </div>
  );
}

function Stat({
  label,
  value,
  note,
  loading,
}: {
  label: string;
  value: string | null;
  note?: string;
  loading: boolean;
}) {
  return (
    <div className="bg-surface-raised px-4 py-3.5">
      <div className="text-[12px] text-content-tertiary">{label}</div>
      {loading ? (
        <div className="skeleton mt-1.5 h-6 w-16" />
      ) : (
        <div className="mt-1 text-xl font-semibold tabular tracking-tight">{value ?? "—"}</div>
      )}
      {note && <div className="mt-0.5 text-[11px] text-content-tertiary">{note}</div>}
    </div>
  );
}

function CurrentSituation({ recall, loading }: { recall: MemoryRecall | null; loading: boolean }) {
  return (
    <Card>
      <CardHeader
        title="Has this happened before?"
        description="The conditions in place right now, matched against what the city has seen previously."
      />
      {loading ? (
        <LoadingState label="Recalling" rows={2} />
      ) : !recall ? (
        <EmptyState
          title="No current fingerprint"
          description="No zone has reported recently enough to characterise the city's conditions."
        />
      ) : (
        <div className="px-5 py-4">
          <div className="mb-3 flex flex-wrap gap-1.5">
            <Badge level="info">{readableBand(recall.rainBand)} rain</Badge>
            <Badge level="info">{readableBand(recall.hourBand)}</Badge>
            <Badge level="info">{readableBand(recall.dayType)}</Badge>
            {recall.hadEvent && <Badge level="info">Event under way</Badge>}
            <Badge level="info">{readableBand(recall.incidentBand)} incidents</Badge>
          </div>

          <p className="text-[13px] leading-relaxed text-content-secondary">{recall.summary}</p>

          {recall.sufficientData ? (
            <>
              <div className="mt-3 grid gap-px overflow-hidden rounded-md border border-line-subtle bg-line-subtle sm:grid-cols-3">
                <Delta label="Congestion" value={recall.medianOccupancyChangePct} inverse />
                <Delta label="Speed" value={recall.medianSpeedChangePct} />
                <Delta label="Risk" value={recall.medianRiskChangePct} inverse />
              </div>
              <p className="mt-2 text-[11px] text-content-tertiary">
                Medians over {recall.matchCount.toLocaleString()} past situations, measured from
                what actually followed — not a forecast.
                {recall.relaxedMatch && " Matched on a widened fingerprint."}
              </p>
            </>
          ) : (
            // Not an error. "We have not seen this before" is a real answer, and
            // a median over three examples dressed up as a finding would be worse.
            <p className="mt-2 text-[11px] text-content-tertiary">
              No outcome figures are shown, because there is not enough history behind this
              combination to support any.
            </p>
          )}
        </div>
      )}
    </Card>
  );
}

/**
 * A median outcome, drawn from the point where nothing changed.
 *
 * Three bare percentages left the reader to work out whether "+8.2%" was a lot,
 * and in which direction it mattered. The bar grows from a centre line at zero,
 * so the sign and the size are read together and a change of nothing is visibly
 * nothing rather than a number that happens to be small.
 *
 * `inverse` exists because the same direction means opposite things: rising
 * congestion and risk are bad, rising speed is good. Colouring by sign alone
 * would paint a recovering city red.
 */
function Delta({
  label,
  value,
  inverse,
}: {
  label: string;
  value: string | null;
  inverse?: boolean;
}) {
  const n = value === null ? null : Number(value);
  const good = n === null ? null : inverse ? n < 0 : n > 0;

  // Clamped at 40%. A single extreme median would otherwise flatten the other
  // two into the axis, and the comparison between them is the point.
  const SPAN = 40;
  const magnitude = n === null ? 0 : Math.min(Math.abs(n), SPAN) / SPAN;

  return (
    <div className="bg-surface-raised px-3 py-2.5">
      <div className="flex items-baseline justify-between gap-2">
        <span className="text-[10px] text-content-tertiary">{label}</span>
        <span
          className={cn(
            "tabular text-[15px] font-medium leading-none",
            good === null
              ? "text-content-disabled"
              : good
                ? "text-status-normal"
                : "text-status-high",
          )}
        >
          {n === null ? "—" : `${n > 0 ? "+" : ""}${n.toFixed(1)}%`}
        </span>
      </div>

      <div className="relative mt-2 h-1.5 rounded-full bg-surface-hover">
        <span aria-hidden="true" className="absolute inset-y-0 left-1/2 w-px bg-line-strong" />
        {n !== null && (
          <span
            aria-hidden="true"
            className={cn(
              "absolute inset-y-0 rounded-full transition-[width]",
              good ? "bg-status-normal" : "bg-status-high",
            )}
            style={
              n >= 0
                ? { left: "50%", width: `${magnitude * 50}%` }
                : { right: "50%", width: `${magnitude * 50}%` }
            }
          />
        )}
      </div>
    </div>
  );
}

function AnomaliesPanel({ anomalies, loading }: { anomalies: AnomalyDetail[]; loading: boolean }) {
  return (
    <Card className="overflow-hidden">
      <CardHeader
        title="Anomalies"
        description="Departures from what each zone normally does at this hour of the week — not fixed thresholds."
      />
      {loading ? (
        <LoadingState label="Loading anomalies" rows={3} />
      ) : anomalies.length === 0 ? (
        <EmptyState
          title="Nothing unusual"
          description="No zone has departed materially from its learned normal in the last 24 hours."
        />
      ) : (
        <ul className="divide-y divide-line-subtle">
          {anomalies.map((anomaly) => (
            <li key={anomaly.id} className="px-5 py-3">
              <div className="flex items-start gap-2.5">
                <Badge level={SEVERITY_BADGE[anomaly.severity]}>{anomaly.severity}</Badge>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-baseline gap-x-2">
                    <span className="text-[13px] font-medium text-content-primary">
                      {anomaly.zoneName}
                    </span>
                    <span className="text-[11px] text-content-tertiary">
                      {METRIC_LABELS[anomaly.metric] ?? anomaly.metric} · {anomaly.anomalyType.toLowerCase()}
                    </span>
                  </div>
                  <p className="mt-0.5 text-[13px] leading-relaxed text-content-secondary">
                    {anomaly.explanation}
                  </p>
                  {/*
                    Sample count shown because a baseline from 20 windows earns
                    less trust than one from 500, and the reader can only apply
                    that judgement if the number is visible.
                  */}
                  <div className="mt-1 flex flex-wrap gap-x-4 text-[11px] text-content-tertiary">
                    <span className="tabular">
                      observed {Number(anomaly.observedValue).toFixed(2)} vs normal{" "}
                      {Number(anomaly.baselineValue).toFixed(2)}
                    </span>
                    <span className="tabular">
                      {Number(anomaly.deviationScore).toFixed(1)}σ from normal
                    </span>
                    <span>baseline from {anomaly.baselineSamples} windows</span>
                    <span>{new Date(anomaly.windowStart).toLocaleString()}</span>
                  </div>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function CorrelationsPanel({
  correlations,
  loading,
}: {
  correlations: Correlation[];
  loading: boolean;
}) {
  return (
    <Card className="overflow-hidden">
      <CardHeader
        title="What tends to occur together"
        description="How often these moved together across the full history. Moving together is not the same as one causing the other."
      />
      {loading ? (
        <LoadingState label="Loading correlations" rows={3} />
      ) : correlations.length === 0 ? (
        <EmptyState
          title="No correlations measured yet"
          description="Run the intelligence jobs: python -m intelligence.jobs correlations"
        />
      ) : (
        // Plotted against 1, where lift means nothing. The list this replaces
        // printed "4.4x, 1.9x, 1.1x" with the reference point nowhere on the
        // page, so a reader had to hold it in their head to tell a finding from
        // noise — and support was absent entirely, which is what separates a
        // strong claim from a rare coincidence.
        <LiftChart correlations={correlations} />
      )}
    </Card>
  );
}

function MemoryExplorer({ citySlug }: { citySlug: string }) {
  const [rainBand, setRainBand] = useState("NONE");
  const [hourBand, setHourBand] = useState("EVENING_PEAK");
  const [dayType, setDayType] = useState("WEEKDAY");
  const [hadEvent, setHadEvent] = useState(false);
  const [incidentBand, setIncidentBand] = useState("NONE");

  const recall = useQuery({
    queryKey: ["memory", citySlug, rainBand, hourBand, dayType, hadEvent, incidentBand],
    queryFn: () =>
      intelligenceApi.memory(citySlug, { rainBand, hourBand, dayType, hadEvent, incidentBand }),
  });

  const select = "rounded-md border border-line-subtle bg-surface-raised px-2 py-1 text-[12px]";

  return (
    <Card className="overflow-hidden">
      <CardHeader
        title="City Memory"
        description="Ask what has historically followed a given combination of conditions."
      />

      <div className="flex flex-wrap gap-2 border-b border-line-subtle px-5 py-3">
        <select value={rainBand} onChange={(e) => setRainBand(e.target.value)} className={select}>
          {RAIN_BANDS.map((b) => (
            <option key={b} value={b}>
              {readableBand(b)} rain
            </option>
          ))}
        </select>
        <select value={hourBand} onChange={(e) => setHourBand(e.target.value)} className={select}>
          {HOUR_BANDS.map((b) => (
            <option key={b} value={b}>
              {readableBand(b)}
            </option>
          ))}
        </select>
        <select value={dayType} onChange={(e) => setDayType(e.target.value)} className={select}>
          <option value="WEEKDAY">Weekday</option>
          <option value="WEEKEND">Weekend</option>
        </select>
        <select
          value={incidentBand}
          onChange={(e) => setIncidentBand(e.target.value)}
          className={select}
        >
          {INCIDENT_BANDS.map((b) => (
            <option key={b} value={b}>
              {readableBand(b)} incidents
            </option>
          ))}
        </select>
        <label className="flex items-center gap-1.5 text-[12px] text-content-secondary">
          <input
            type="checkbox"
            checked={hadEvent}
            onChange={(e) => setHadEvent(e.target.checked)}
            className="rounded border-line-subtle"
          />
          Event under way
        </label>
      </div>

      {recall.isLoading ? (
        <LoadingState label="Searching memory" rows={2} />
      ) : !recall.data ? (
        <EmptyState title="No result" description="Could not query the memory." />
      ) : (
        <div className="px-5 py-4">
          <p className="text-[13px] leading-relaxed text-content-secondary">{recall.data.summary}</p>

          {recall.data.sufficientData && recall.data.examples.length > 0 && (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-left text-[12px]">
                <thead>
                  <tr className="border-b border-line-subtle text-[11px] text-content-tertiary">
                    <th scope="col" className="py-1.5 pr-4 font-medium">When</th>
                    <th scope="col" className="py-1.5 pr-4 font-medium">Zone</th>
                    <th scope="col" className="py-1.5 pr-4 text-right font-medium">Congestion</th>
                    <th scope="col" className="py-1.5 text-right font-medium">Speed</th>
                  </tr>
                </thead>
                <tbody>
                  {recall.data.examples.map((e) => (
                    <tr key={`${e.zoneCode}-${e.occurredAt}`} className="border-b border-line-subtle last:border-0">
                      <td className="py-1.5 pr-4 text-content-tertiary">
                        {new Date(e.occurredAt).toLocaleString()}
                      </td>
                      <td className="py-1.5 pr-4">{e.zoneCode}</td>
                      <td className="py-1.5 pr-4 text-right tabular">
                        {e.occupancyChangePct === null
                          ? "—"
                          : `${Number(e.occupancyChangePct) > 0 ? "+" : ""}${Number(e.occupancyChangePct).toFixed(0)}%`}
                      </td>
                      <td className="py-1.5 text-right tabular">
                        {e.speedChangePct === null
                          ? "—"
                          : `${Number(e.speedChangePct) > 0 ? "+" : ""}${Number(e.speedChangePct).toFixed(0)}%`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
