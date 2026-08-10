"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useMemo, useState } from "react";
import {
  Badge,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  Input,
  LoadingState,
  PageHeader,
  Skeleton,
  cn,
} from "@/components/ui";
import { Sparkline } from "@/components/charts/Sparkline";
import { intelligenceApi, liveApi } from "@/lib/api/endpoints";
import type { AnomalyDetail } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";
import { define } from "@/lib/wording";

/**
 * Anomaly Detection — an investigation workspace over what the platform found.
 *
 * An anomaly here is not a threshold being crossed. It is a reading that departs
 * from what *this* zone normally does at *this* hour of the week, measured
 * against a baseline of median and MAD rather than mean and standard deviation —
 * so a week containing the anomaly cannot quietly absorb it into "normal".
 * Alerts are the threshold mechanism; these are the statistical one, and the two
 * answer different questions.
 *
 * Every figure on this page is a stored column. Nothing is recomputed in the
 * browser, and nothing is asserted that detection did not record: the reason
 * shown is the explanation written at detection time, so it stays true as the
 * detector changes.
 */

const SEVERITIES = ["CRITICAL", "HIGH", "MEDIUM", "LOW"] as const;
type Severity = (typeof SEVERITIES)[number];

const SEVERITY_TO_STATUS = {
  CRITICAL: "critical",
  HIGH: "high",
  MEDIUM: "moderate",
  LOW: "normal",
} as const;

const RANGES = [
  { label: "6H", hours: 6 },
  { label: "24H", hours: 24 },
  { label: "3D", hours: 72 },
  { label: "7D", hours: 168 },
] as const;

const METRIC_LABEL: Record<string, string> = {
  occupancy_ratio: "Congestion",
  average_speed_kph: "Average speed",
  vehicle_count: "Vehicle volume",
  risk_score: "Overall risk",
};

const METRIC_SCALE: Record<string, number> = { occupancy_ratio: 100 };
const METRIC_UNIT: Record<string, string> = {
  occupancy_ratio: "% of capacity",
  average_speed_kph: "km/h",
  vehicle_count: "vehicles",
  risk_score: "/ 100",
};

const TYPE_LABEL: Record<string, string> = {
  SPIKE: "Spike",
  DROP: "Drop",
  SUSTAINED_SHIFT: "Sustained shift",
};

function scaled(value: string, metric: string): number {
  return Number(value) * (METRIC_SCALE[metric] ?? 1);
}

function decimalsFor(metric: string): number {
  return METRIC_SCALE[metric] === 100 ? 0 : 1;
}

export default function AnomaliesPage() {
  const { city } = useSelectedCity();
  const [hours, setHours] = useState<number>(24);
  const [severity, setSeverity] = useState<Severity | "ALL">("ALL");
  const [query, setQuery] = useState("");
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const anomaliesQuery = useQuery({
    queryKey: ["anomalies-page", city?.slug, hours],
    queryFn: () => intelligenceApi.anomalies(city!.slug, hours, 200),
    enabled: Boolean(city),
    staleTime: 60_000,
  });

  const all = useMemo(() => anomaliesQuery.data?.items ?? [], [anomaliesQuery.data]);

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    return all.filter((a) => {
      if (severity !== "ALL" && a.severity !== severity) return false;
      if (!needle) return true;
      return (
        a.zoneName.toLowerCase().includes(needle) ||
        a.zoneCode.toLowerCase().includes(needle) ||
        (METRIC_LABEL[a.metric] ?? a.metric).toLowerCase().includes(needle)
      );
    });
  }, [all, severity, query]);

  const selected = visible.find((a) => a.id === selectedId) ?? visible[0] ?? null;

  if (!city) return <LoadingState label="Loading city" rows={4} />;

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Anomaly Detection"
        subtitle={<>{city.name} · places behaving unlike themselves, measured against their own usual week</>}
        actions={
          <div className="flex items-center gap-1" role="group" aria-label="Time range">
          {RANGES.map((range) => (
            <button
              key={range.label}
              type="button"
              onClick={() => setHours(range.hours)}
              aria-pressed={hours === range.hours}
              className={cn(
                "rounded-md border px-2.5 py-1 text-[11px] font-medium transition-colors",
                hours === range.hours
                  ? "border-accent/40 bg-accent-subtle text-accent"
                  : "border-line-default text-content-secondary hover:bg-surface-hover hover:text-content-primary",
              )}
            >
              {range.label}
            </button>
          ))}
        </div>
        }
      />

      {anomaliesQuery.isError ? (
        <ErrorState
          title="Could not load anomalies"
          message={anomaliesQuery.error instanceof Error ? anomaliesQuery.error.message : "Unavailable."}
          onRetry={() => void anomaliesQuery.refetch()}
        />
      ) : anomaliesQuery.isLoading ? (
        <LoadingState label="Loading anomalies" rows={6} />
      ) : (
        <>
          <SeverityBreakdown anomalies={all} hours={hours} />

          <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_minmax(0,22rem)]">
            <Card className="overflow-hidden">
              <CardHeader
                title="Detections"
                description={`${visible.length} of ${all.length} in the last ${hours} hours`}
              />
              <div className="flex flex-wrap items-center gap-2 border-b border-line-subtle px-5 py-3">
                <Input
                  label="Search anomalies"
                  hideLabel
                  value={query}
                  onChange={(e) => setQuery(e.target.value)}
                  placeholder="Search zone or metric"
                  className="h-8 w-full max-w-[220px] text-[12px]"
                />
                <Chip active={severity === "ALL"} onClick={() => setSeverity("ALL")}>
                  All
                </Chip>
                {SEVERITIES.map((level) => {
                  const count = all.filter((a) => a.severity === level).length;
                  return (
                    <Chip
                      key={level}
                      active={severity === level}
                      disabled={count === 0}
                      onClick={() => setSeverity(severity === level ? "ALL" : level)}
                    >
                      <span
                        aria-hidden="true"
                        className="mr-1.5 inline-block h-1.5 w-1.5 rounded-full align-middle"
                        style={{ background: `var(--color-status-${SEVERITY_TO_STATUS[level]})` }}
                      />
                      {level.charAt(0) + level.slice(1).toLowerCase()} {count}
                    </Chip>
                  );
                })}
              </div>

              {visible.length === 0 ? (
                <EmptyState
                  title={all.length === 0 ? "No anomalies detected" : "Nothing matches"}
                  description={
                    all.length === 0
                      ? "No zone departed from its baseline in this range. A zone whose feed has stopped cannot produce an anomaly, so check coverage before reading this as quiet."
                      : "No detection matches the current search and filter."
                  }
                />
              ) : (
                <ul className="max-h-[560px] divide-y divide-line-subtle overflow-y-auto">
                  {visible.map((anomaly) => (
                    <DetectionRow
                      key={anomaly.id}
                      anomaly={anomaly}
                      selected={anomaly.id === selected?.id}
                      onSelect={() => setSelectedId(anomaly.id)}
                    />
                  ))}
                </ul>
              )}
            </Card>

            <div className="space-y-5">
              <AnomalyDetailPanel anomaly={selected} />
              <ZoneRanking anomalies={all} onSelectZone={(id) => setSelectedId(id)} />
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/**
 * How many, and how bad — with the range stated.
 *
 * A count without its window is not a measurement: "12 anomalies" means very
 * different things over six hours and over a week.
 */
function SeverityBreakdown({ anomalies, hours }: { anomalies: AnomalyDetail[]; hours: number }) {
  const counts = SEVERITIES.map((level) => ({
    level,
    count: anomalies.filter((a) => a.severity === level).length,
  }));
  const total = anomalies.length;

  return (
    <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-5">
      <div className="bg-surface-raised px-5 py-4">
        <div className="text-[10px] font-medium uppercase tracking-[0.08em] text-content-tertiary">
          Detected
        </div>
        <div className="mt-1 tabular text-[22px] font-semibold leading-none text-content-primary">
          {total}
        </div>
        <div className="mt-1.5 text-[11px] text-content-tertiary">in the last {hours}h</div>
      </div>
      {counts.map(({ level, count }) => (
        <div key={level} className="bg-surface-raised px-5 py-4">
          <div className="text-[10px] font-medium uppercase tracking-[0.08em] text-content-tertiary">
            {level.charAt(0) + level.slice(1).toLowerCase()}
          </div>
          <div
            className={cn(
              "mt-1 tabular text-[22px] font-semibold leading-none",
              count === 0 ? "text-content-disabled" : `text-status-${SEVERITY_TO_STATUS[level]}`,
            )}
          >
            {count}
          </div>
          <div className="mt-1.5 text-[11px] text-content-tertiary">
            {total === 0 ? "—" : `${((count / total) * 100).toFixed(0)}% of detections`}
          </div>
        </div>
      ))}
    </div>
  );
}

function DetectionRow({
  anomaly,
  selected,
  onSelect,
}: {
  anomaly: AnomalyDetail;
  selected: boolean;
  onSelect: () => void;
}) {
  const status = SEVERITY_TO_STATUS[anomaly.severity as Severity] ?? "moderate";
  const d = decimalsFor(anomaly.metric);
  const change = anomaly.percentChange === null ? null : Number(anomaly.percentChange);

  return (
    <li>
      <button
        type="button"
        onClick={onSelect}
        aria-current={selected ? "true" : undefined}
        className={cn(
          "relative flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-5 py-3 text-left transition-colors",
          selected ? "bg-accent-subtle" : "hover:bg-surface-hover",
        )}
      >
        <span aria-hidden="true" className={cn("absolute inset-y-0 left-0 w-[3px]", `bg-status-${status}`)} />
        <Badge level={status}>{anomaly.severity.charAt(0) + anomaly.severity.slice(1).toLowerCase()}</Badge>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-[13px] text-content-primary">{anomaly.zoneName}</span>
          <span className="text-[11px] text-content-tertiary">
            {METRIC_LABEL[anomaly.metric] ?? anomaly.metric} ·{" "}
            {TYPE_LABEL[anomaly.anomalyType] ?? anomaly.anomalyType}
          </span>
        </span>
        <span className="tabular text-right text-[12px]">
          <span className={cn("font-medium", `text-status-${status}`)}>
            {scaled(anomaly.observedValue, anomaly.metric).toFixed(d)}
          </span>
          <span className="text-content-tertiary">
            {" "}vs {scaled(anomaly.baselineValue, anomaly.metric).toFixed(d)}
          </span>
        </span>
        <span className="w-[54px] shrink-0 text-right tabular text-[11px] text-content-secondary">
          {change === null ? "—" : `${change > 0 ? "+" : ""}${change.toFixed(0)}%`}
        </span>
        <span className="w-[62px] shrink-0 text-right text-[10px] text-content-tertiary">
          {new Date(anomaly.windowStart).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" })}
        </span>
      </button>
    </li>
  );
}

/**
 * One detection in full, with the zone's recent shape around it.
 *
 * The history sparkline is the context the numbers cannot give: a reading 40%
 * above baseline that has been climbing for an hour is a different situation
 * from one that spiked in a single window, and both print the same percentage.
 */
function AnomalyDetailPanel({ anomaly }: { anomaly: AnomalyDetail | null }) {
  const historyQuery = useQuery({
    queryKey: ["anomaly-zone-history", anomaly?.zoneId],
    queryFn: () => liveApi.history(anomaly!.zoneId),
    enabled: Boolean(anomaly),
    staleTime: 60_000,
  });

  if (!anomaly) {
    return (
      <Card>
        <CardHeader title="Detection detail" />
        <EmptyState
          title="No detection selected"
          description="Choose a detection to see the reading, the baseline it broke, and the zone's recent shape."
        />
      </Card>
    );
  }

  const status = SEVERITY_TO_STATUS[anomaly.severity as Severity] ?? "moderate";
  const d = decimalsFor(anomaly.metric);
  const unit = METRIC_UNIT[anomaly.metric] ?? "";
  const observed = scaled(anomaly.observedValue, anomaly.metric);
  const baseline = scaled(anomaly.baselineValue, anomaly.metric);

  const points = historyQuery.data?.points ?? [];
  const series = points.map((p) => {
    switch (anomaly.metric) {
      case "occupancy_ratio":
        return p.occupancyRatio === null ? null : Number(p.occupancyRatio) * 100;
      case "average_speed_kph":
        return p.averageSpeedKph === null ? null : Number(p.averageSpeedKph);
      case "risk_score":
        return p.riskScore === null ? null : Number(p.riskScore);
      default:
        // Vehicle count is not stored per curated window. No stand-in.
        return null;
    }
  });

  return (
    <Card className="overflow-hidden">
      <CardHeader
        title={anomaly.zoneName}
        description={`${METRIC_LABEL[anomaly.metric] ?? anomaly.metric} · ${TYPE_LABEL[anomaly.anomalyType] ?? anomaly.anomalyType}`}
        action={<Badge level={status}>{anomaly.severity.charAt(0) + anomaly.severity.slice(1).toLowerCase()}</Badge>}
      />

      <div className="grid grid-cols-3 gap-3 border-b border-line-subtle px-5 py-4">
        <Figure label="Observed" value={observed.toFixed(d)} unit={unit} tone={status} />
        <Figure label="Expected" value={baseline.toFixed(d)} unit={unit} />
        {/* "scaled MADs" was the unit on screen. It is the correct name for the
            quantity and tells a reader nothing; the number it labelled is the
            whole reason this screen exists. The plain unit says what the figure
            is a multiple of, and the exact term is on the label. */}
        <Figure
          label="How unusual"
          labelTitle={define("deviation")}
          value={Number(anomaly.deviationScore).toFixed(1)}
          unit="× usual spread"
        />
      </div>

      <section className="border-b border-line-subtle px-5 py-4">
        <Label>Recent shape</Label>
        {series.filter((v) => v !== null).length >= 2 ? (
          <div className="mt-2">
            <Sparkline points={series} width={260} height={44} ariaLabel="Zone history around the detection" />
            <p className="mt-1.5 text-[10px] text-content-tertiary">
              The zone&rsquo;s recent readings. A reading that climbed for an hour and one
              that spiked in a single window print the same percentage.
            </p>
          </div>
        ) : historyQuery.isLoading ? (
          // Sibling of the <p> rather than inside it: Skeleton renders a <div>,
          // which a <p> may not contain. The browser silently closed the
          // paragraph early, so the server and client markup disagreed and
          // hydration failed on every visit to this panel.
          <Skeleton className="mt-1.5 h-8 w-40" />
        ) : (
          <p className="mt-1.5 text-[12px] text-content-disabled">Insufficient history to plot</p>
        )}
      </section>

      <section className="border-b border-line-subtle px-5 py-4">
        <Label>Why this was flagged</Label>
        {/* Written at detection time, so it stays true as the detector changes. */}
        <p className="mt-1.5 text-[12px] leading-relaxed text-content-secondary">
          {anomaly.explanation}
        </p>
        <p className="mt-2 text-[10px] text-content-tertiary">
          Baseline is the median and MAD of {anomaly.baselineSamples} historical windows for this
          zone at this hour of the week — not a fixed threshold, which is what alerts use.
        </p>
      </section>

      <section className="px-5 py-4">
        <Label>Contributing signals</Label>
        {/* Detection records a metric and a departure. It does not attribute a
            cause, and inventing one here would be the most convincing possible
            way to be wrong. */}
        <p className="mt-1.5 text-[12px] text-content-disabled">
          Insufficient evidence — detection records the departure, not its cause. The correlations
          on{" "}
          <Link href="/insights" className="text-accent hover:underline">
            AI Insights
          </Link>{" "}
          measure which conditions co-occur, and state explicitly that co-occurrence is not
          causation.
        </p>

        <div className="mt-3 flex flex-wrap gap-1.5">
          <Link
            href="/command-center"
            className="rounded-md border border-accent/40 bg-accent-subtle px-2.5 py-1 text-[11px] font-medium text-accent transition-colors hover:bg-accent-muted"
          >
            View zone
          </Link>
          <Link
            href="/forecast"
            className="rounded-md border border-line-default px-2.5 py-1 text-[11px] text-content-secondary transition-colors hover:bg-surface-hover hover:text-content-primary"
          >
            View forecast
          </Link>
          <Link
            href="/simulator"
            className="rounded-md border border-line-default px-2.5 py-1 text-[11px] text-content-secondary transition-colors hover:bg-surface-hover hover:text-content-primary"
          >
            Run simulation
          </Link>
        </div>
      </section>
    </Card>
  );
}

/** Which zones are producing detections — where to look, not just what happened. */
function ZoneRanking({
  anomalies,
  onSelectZone,
}: {
  anomalies: AnomalyDetail[];
  onSelectZone: (anomalyId: string) => void;
}) {
  const byZone = new Map<string, { name: string; count: number; worst: AnomalyDetail }>();
  anomalies.forEach((a) => {
    const held = byZone.get(a.zoneId);
    if (!held) {
      byZone.set(a.zoneId, { name: a.zoneName, count: 1, worst: a });
    } else {
      held.count += 1;
      if (Number(a.deviationScore) > Number(held.worst.deviationScore)) held.worst = a;
    }
  });

  const ranked = [...byZone.values()].sort((a, b) => b.count - a.count).slice(0, 8);
  const max = ranked[0]?.count ?? 1;

  if (ranked.length === 0) return null;

  return (
    <Card className="overflow-hidden">
      <CardHeader title="Zones by detections" description="Where the departures are concentrated." />
      <ul className="px-5 pb-4 pt-1">
        {ranked.map((row) => (
          <li key={row.name}>
            <button
              type="button"
              onClick={() => onSelectZone(row.worst.id)}
              className="flex w-full items-center gap-3 py-1.5 text-left"
            >
              <span className="w-[104px] shrink-0 truncate text-[11px] text-content-secondary">
                {row.name}
              </span>
              <span className="h-1.5 flex-1 overflow-hidden rounded-full bg-surface-hover">
                <span
                  className="block h-full rounded-full"
                  style={{
                    width: `${(row.count / max) * 100}%`,
                    background: `var(--color-status-${SEVERITY_TO_STATUS[row.worst.severity as Severity] ?? "moderate"})`,
                  }}
                />
              </span>
              <span className="w-6 shrink-0 text-right tabular text-[11px] text-content-secondary">
                {row.count}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Figure({
  label,
  labelTitle,
  value,
  unit,
  tone,
}: {
  label: string;
  /** The exact definition, on the label. Dotted underline marks that it exists. */
  labelTitle?: string;
  value: string;
  unit?: string;
  tone?: string;
}) {
  return (
    <div>
      <Label>
        <span
          title={labelTitle}
          className={cn(labelTitle && "cursor-help decoration-dotted underline-offset-4 hover:underline")}
        >
          {label}
        </span>
      </Label>
      <div className="mt-1 flex items-baseline gap-1">
        <span className={cn("tabular text-[19px] font-semibold leading-none", tone ? `text-status-${tone}` : "text-content-primary")}>
          {value}
        </span>
      </div>
      {unit && <div className="mt-1 text-[10px] text-content-tertiary">{unit}</div>}
    </div>
  );
}

function Label({ children }: { children: React.ReactNode }) {
  return (
    <span className="text-[10px] font-medium uppercase tracking-[0.09em] text-content-tertiary">
      {children}
    </span>
  );
}

function Chip({
  active,
  disabled,
  onClick,
  children,
}: {
  active: boolean;
  disabled?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-pressed={active}
      className={cn(
        "rounded-md border px-2 py-1 text-[11px] transition-colors",
        active
          ? "border-accent/40 bg-accent-subtle text-accent"
          : "border-line-default text-content-secondary hover:bg-surface-hover hover:text-content-primary",
        disabled && "cursor-not-allowed opacity-40 hover:bg-transparent",
      )}
    >
      {children}
    </button>
  );
}
