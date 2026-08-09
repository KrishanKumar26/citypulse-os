"use client";

import { useQuery } from "@tanstack/react-query";
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
import { interventionApi } from "@/lib/api/endpoints";
import type { Impact, Intervention, MetricImpact } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";

/**
 * Did it work?
 *
 * <p>The last stage of the loop the rest of the product builds toward, and the
 * easiest place in it to manufacture a success story — so most of this screen is
 * about what the measurement will not claim.
 *
 * <p>The headline figure is not the before/after difference. Congestion falls in
 * the evening whether or not anyone intervened, so the raw change credits the
 * action with the sunset. What is shown large is the movement *beyond* what the
 * zone's own baseline for those hours already predicted; the raw change sits
 * beside it in smaller type, because it is context rather than the answer.
 *
 * <p>An intervention with no windows on one side is reported as unmeasurable,
 * not as ineffective. An action taken during a feed outage would otherwise score
 * however the missing data happened to average.
 */

const METRIC_LABEL: Record<string, string> = {
  occupancy_ratio: "Congestion",
  average_speed_kph: "Average speed",
  risk_score: "Composite risk",
};

const METRIC_SCALE: Record<string, number> = { occupancy_ratio: 100 };
const METRIC_UNIT: Record<string, string> = {
  occupancy_ratio: "% of capacity",
  average_speed_kph: "km/h",
  risk_score: "/ 100",
};

/** Rising congestion and risk are bad; rising speed is good. */
const HIGHER_IS_WORSE: Record<string, boolean> = {
  occupancy_ratio: true,
  average_speed_kph: false,
  risk_score: true,
};

function scaled(value: string | null, metric: string): number | null {
  return value === null ? null : Number(value) * (METRIC_SCALE[metric] ?? 1);
}

export default function ImpactPage() {
  const { city } = useSelectedCity();

  const query = useQuery({
    queryKey: ["interventions", city?.slug],
    queryFn: () => interventionApi.list(city!.slug),
    enabled: Boolean(city),
    refetchInterval: 120_000,
  });

  if (!city) return <LoadingState label="Loading city" rows={4} />;

  const interventions = query.data ?? [];

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Impact"
        subtitle={<>{city.name} · actions taken, and what the city did afterwards</>}
      />

      {query.isError ? (
        <ErrorState
          title="Could not load interventions"
          message={query.error instanceof Error ? query.error.message : "Unavailable."}
          onRetry={() => void query.refetch()}
        />
      ) : query.isLoading ? (
        <LoadingState label="Loading interventions" rows={4} />
      ) : interventions.length === 0 ? (
        <EmptyState
          title="Nothing recorded yet"
          description="The platform cannot observe an action — someone records that one was taken, and it then measures what followed against the zone's own baseline."
        />
      ) : (
        <div className="space-y-4">
          {interventions.map((i) => (
            <InterventionCard key={i.id} intervention={i} />
          ))}
        </div>
      )}

      <p className="text-[11px] leading-relaxed text-content-tertiary">
        These are measured coincidences between a stated action and a departure from normal.
        Nothing here establishes that the action caused the change: the platform compares what
        followed against what this zone usually does at these hours, and reports the difference.
        Deciding whether the action was responsible is a judgement it does not make.
      </p>
    </div>
  );
}

function InterventionCard({ intervention }: { intervention: Intervention }) {
  const running = intervention.endedAt === null;

  return (
    <Card className="overflow-hidden">
      <CardHeader
        title={intervention.title}
        description={
          [
            intervention.zoneName ?? "City-wide",
            intervention.actionType.replace(/_/g, " ").toLowerCase(),
            `recorded by ${intervention.recordedBy}`,
          ].join(" · ")
        }
        action={
          <Badge level={running ? "info" : "neutral"}>
            {running ? "In effect" : intervention.status.toLowerCase()}
          </Badge>
        }
      />

      <div className="px-5 py-3 text-[11px] text-content-tertiary">
        Started {new Date(intervention.startedAt).toLocaleString()}
        {intervention.endedAt && <> · ended {new Date(intervention.endedAt).toLocaleString()}</>}
        {" · compared over "}
        {intervention.comparisonMinutes} minutes either side
      </div>

      <ImpactPanel impact={intervention.impact} />
    </Card>
  );
}

function ImpactPanel({ impact }: { impact: Impact | null }) {
  if (impact === null) {
    // A city-wide action has no zone baseline. Inventing a city-level normal
    // that was never learned would be worse than saying nothing.
    return (
      <p className="border-t border-line-subtle px-5 py-4 text-[12px] text-content-tertiary">
        No impact is measured for a city-wide action — there is no zone baseline to compare it
        against, and the platform never learned a city-level one.
      </p>
    );
  }

  if (!impact.measurable) {
    // Unmeasurable, not ineffective. The difference matters: an action taken
    // during an outage would otherwise score however the gap averaged.
    return (
      <div className="border-t border-line-subtle px-5 py-4">
        <p className="text-[12px] text-content-secondary">
          {impact.unmeasurableReason}
        </p>
        <p className="mt-1 text-[11px] text-content-tertiary">
          This is not a finding of no effect. With {impact.windowsBefore} windows before and{" "}
          {impact.windowsAfter} after, there is nothing to compare.
        </p>
      </div>
    );
  }

  return (
    <div className="border-t border-line-subtle">
      {impact.provisional && (
        <p className="border-b border-line-subtle bg-surface-overlay px-5 py-2 text-[11px] text-content-secondary">
          Still in effect — the window after it is still filling, so these figures will move.
        </p>
      )}

      <div className="grid gap-px bg-line-subtle sm:grid-cols-3">
        {impact.metrics.map((m) => (
          <MetricPanel key={m.metric} metric={m} />
        ))}
      </div>

      <p className="px-5 py-3 text-[10px] text-content-tertiary">
        {impact.windowsBefore} curated windows before, {impact.windowsAfter} after.
      </p>
    </div>
  );
}

function MetricPanel({ metric }: { metric: MetricImpact }) {
  const label = METRIC_LABEL[metric.metric] ?? metric.metric;
  const unit = METRIC_UNIT[metric.metric] ?? "";
  const decimals = METRIC_SCALE[metric.metric] === 100 ? 0 : 1;

  const before = scaled(metric.before, metric.metric);
  const after = scaled(metric.after, metric.metric);
  const raw = metric.changePct === null ? null : Number(metric.changePct);
  const excess = metric.excessChangePct === null ? null : Number(metric.excessChangePct);

  const worseWhenUp = HIGHER_IS_WORSE[metric.metric] ?? true;
  const good = excess === null ? null : worseWhenUp ? excess < 0 : excess > 0;

  return (
    <div className="bg-surface-raised px-5 py-4">
      <div className="text-[10px] font-medium uppercase tracking-[0.09em] text-content-tertiary">
        {label}
      </div>

      {/* The figure that says something about the action: the movement the
          baseline does not already explain. */}
      <div className="mt-1.5 flex items-baseline gap-1.5">
        {excess === null ? (
          <span className="text-[13px] text-content-disabled">no baseline</span>
        ) : (
          <>
            <span
              className={cn(
                "tabular text-[27px] font-semibold leading-none",
                good ? "text-status-normal" : "text-status-high",
              )}
            >
              {excess > 0 ? "+" : ""}
              {excess.toFixed(1)}%
            </span>
            <span className="text-[10px] text-content-tertiary">beyond normal</span>
          </>
        )}
      </div>

      {/* Context, in smaller type. The raw change includes whatever the city was
          going to do anyway. */}
      <dl className="mt-3 space-y-1 text-[11px]">
        <div className="flex justify-between gap-2">
          <dt className="text-content-tertiary">Before → after</dt>
          <dd className="tabular text-content-secondary">
            {before === null || after === null
              ? "—"
              : `${before.toFixed(decimals)} → ${after.toFixed(decimals)} ${unit}`}
          </dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-content-tertiary">Raw change</dt>
          <dd className="tabular text-content-secondary">
            {raw === null ? "—" : `${raw > 0 ? "+" : ""}${raw.toFixed(1)}%`}
          </dd>
        </div>
        <div className="flex justify-between gap-2">
          <dt className="text-content-tertiary">Usual at these hours</dt>
          <dd className="tabular text-content-secondary">
            {metric.baseline === null
              ? "not learned"
              : `${scaled(metric.baseline, metric.metric)!.toFixed(decimals)} ${unit}`}
          </dd>
        </div>
      </dl>

      {metric.baselineSamples > 0 && (
        <p className="mt-2 text-[10px] text-content-tertiary">
          baseline from {metric.baselineSamples.toLocaleString()} windows
        </p>
      )}
    </div>
  );
}
