"use client";

import { useQuery } from "@tanstack/react-query";
import { useMemo, useState } from "react";
import {
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  Skeleton,
  cn,
} from "@/components/ui";
import { TimeSeriesChart, type SeriesPoint } from "@/components/charts/TimeSeriesChart";
import { RiskDistribution, ZoneRiskChart } from "@/components/charts/ZoneRiskChart";
import { liveApi } from "@/lib/api/endpoints";
import type { CityHistory } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";
import { useLiveSnapshot } from "@/lib/live/useLiveSnapshot";

/**
 * City Analytics.
 *
 * Four series over a chosen range, plus the current cross-section by zone. Each
 * chart answers one question and nothing else — the brief's warning against
 * decorative charts is the right one, and a page of six plots that all say
 * "traffic was busy this evening" is worse than three that say different things.
 *
 * Every number here comes from the same aggregated endpoint the KPI sparklines
 * use, so a figure on this page and the same figure on the Command Center cannot
 * disagree. Both average only across the zones that reported in each window.
 */

const RANGES = [
  { label: "1H", hours: 1 },
  { label: "6H", hours: 6 },
  { label: "24H", hours: 24 },
  { label: "7D", hours: 24 * 7 },
  { label: "30D", hours: 24 * 30 },
] as const;

export default function AnalyticsPage() {
  const { city } = useSelectedCity();
  const [hours, setHours] = useState<number>(6);

  const historyQuery = useQuery({
    queryKey: ["analytics-history", city?.slug, hours],
    queryFn: () => {
      const to = new Date();
      const from = new Date(to.getTime() - hours * 3600_000);
      return liveApi.cityHistory(city!.slug, from.toISOString(), to.toISOString());
    },
    enabled: Boolean(city),
    staleTime: 2 * 60_000,
  });

  const { snapshot } = useLiveSnapshot(city?.slug ?? null);

  const series = useMemo(() => build(historyQuery.data ?? null), [historyQuery.data]);

  if (!city) return <LoadingState label="Loading city" rows={4} />;

  const bucket = historyQuery.data?.bucketMinutes;

  return (
    <div className="space-y-5 p-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-lg font-semibold tracking-tight">City Analytics</h1>
          <p className="mt-1 text-[13px] text-content-tertiary">
            {city.name} · every series averaged across the zones that reported
          </p>
        </div>

        <div className="flex items-center gap-1" role="group" aria-label="Time range">
          {RANGES.map((range) => (
            <button
              key={range.label}
              type="button"
              onClick={() => setHours(range.hours)}
              aria-pressed={hours === range.hours}
              className={cn(
                "rounded-md border px-2.5 py-1 text-[11.5px] font-medium transition-colors",
                hours === range.hours
                  ? "border-accent/40 bg-accent-subtle text-accent"
                  : "border-line-default text-content-secondary hover:bg-surface-hover hover:text-content-primary",
              )}
            >
              {range.label}
            </button>
          ))}
        </div>
      </header>

      {/* Stated, not hidden. On a long range each point is an average of many
          windows, and a reader comparing this chart to the live tile deserves to
          know they are not the same resolution. */}
      {bucket != null && bucket > 5 && (
        <p className="text-[11.5px] text-content-tertiary">
          Each point is a {bucket >= 60 ? `${bucket / 60}-hour` : `${bucket}-minute`} average.
          The curated window is 5 minutes; this range is folded so all of it is shown rather
          than the first part of it.
        </p>
      )}

      {historyQuery.isError ? (
        <ErrorState
          title="Could not load history"
          message={historyQuery.error instanceof Error ? historyQuery.error.message : "Unavailable."}
          onRetry={() => void historyQuery.refetch()}
        />
      ) : historyQuery.isLoading ? (
        <div className="grid gap-5 lg:grid-cols-2">
          {[0, 1, 2, 3].map((i) => <Skeleton key={i} className="h-[260px]" />)}
        </div>
      ) : series === null || series.congestion.length === 0 ? (
        <EmptyState
          title="No readings in this range"
          description="Nothing was curated for this city in the selected window. A shorter range may have data; the hosted deployment refreshes hourly."
        />
      ) : (
        <div className="grid gap-5 lg:grid-cols-2">
          <Panel title="Traffic congestion" description="Share of road capacity in use, city-wide.">
            <TimeSeriesChart points={series.congestion} unit="% of capacity" decimals={0}
                             bucketMinutes={bucket} area />
          </Panel>

          <Panel title="Composite risk" description="The score the Command Center reports, over time.">
            <TimeSeriesChart points={series.risk} unit="/ 100" decimals={0}
                             color="var(--color-status-high)" bucketMinutes={bucket} area />
          </Panel>

          <Panel title="Average speed" description="Mean speed across reporting zones.">
            <TimeSeriesChart points={series.speed} unit="km/h" decimals={1}
                             color="var(--color-status-normal)" bucketMinutes={bucket} />
          </Panel>

          <Panel
            title="Coverage"
            description="Zones contributing to each point. A dip here means the platform saw less, not that the city was quieter."
          >
            <TimeSeriesChart points={series.coverage} unit="zones" decimals={0}
                             color="var(--color-content-tertiary)" bucketMinutes={bucket} />
          </Panel>
        </div>
      )}

      <Card className="overflow-hidden">
        <CardHeader
          title="Risk by zone, now"
          description="The current cross-section. The series above are the city as a whole; this is where it is concentrated."
        />
        {snapshot ? (
          <>
            <RiskDistribution zones={snapshot.zones} />
            <ZoneRiskChart zones={snapshot.zones} />
          </>
        ) : (
          <Skeleton className="m-5 h-40" />
        )}
      </Card>
    </div>
  );
}

function Panel({
  title,
  description,
  children,
}: {
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <Card className="overflow-hidden">
      <CardHeader title={title} description={description} />
      <div className="px-4 pb-4 pt-2">{children}</div>
    </Card>
  );
}

function build(history: CityHistory | null) {
  if (!history) return null;
  const at = (pick: (p: CityHistory["points"][number]) => number | null): SeriesPoint[] =>
    history.points.map((p) => ({ t: new Date(p.windowStart).getTime(), v: pick(p) }));

  return {
    congestion: at((p) => (p.averageCongestion === null ? null : Number(p.averageCongestion) * 100)),
    risk: at((p) => (p.averageRiskScore === null ? null : Number(p.averageRiskScore))),
    speed: at((p) => (p.averageSpeedKph === null ? null : Number(p.averageSpeedKph))),
    coverage: at((p) => p.reportingZones),
  };
}
