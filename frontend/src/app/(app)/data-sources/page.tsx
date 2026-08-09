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
import { dataSourceApi } from "@/lib/api/endpoints";
import type { DataSourceSummary } from "@/lib/api/types";
import { PROVENANCE_DETAIL, PROVENANCE_LABEL, provenanceLevel } from "@/lib/provenance";

/**
 * Where the numbers came from.
 *
 * Every other page in the product presents derived figures. This is the one
 * that says what fed them, and whether those feeds are actually running — which
 * is the question behind "why is the dashboard empty", and until now had no
 * answer inside the product at all.
 *
 * The column that matters is rows delivered, not status. A source's status is a
 * configuration and its last-ingested timestamp is written by whatever writes
 * the events, so a retry can advance it without a row arriving. Counting rows
 * cannot be wrong in that direction, and where the two disagree the count is the
 * measurement and the rest is a claim.
 */

const TYPE_LABEL: Record<string, string> = {
  TRAFFIC: "Traffic",
  WEATHER: "Weather",
  AIR_QUALITY: "Air quality",
  INCIDENT: "Incidents",
  CITY_EVENT: "City events",
};

function relativeAge(seconds: number | null): string {
  if (seconds === null) return "never";
  if (seconds < 90) return `${Math.max(seconds, 0)}s ago`;
  if (seconds < 5400) return `${Math.round(seconds / 60)}m ago`;
  if (seconds < 172800) return `${Math.round(seconds / 3600)}h ago`;
  return `${Math.round(seconds / 86400)}d ago`;
}

export default function DataSourcesPage() {
  const query = useQuery({
    queryKey: ["data-sources"],
    queryFn: () => dataSourceApi.list(),
    refetchInterval: 60_000,
  });

  const data = query.data;

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Data Sources"
        subtitle={<>The feeds behind every figure in the product, and whether they are delivering.</>}
      />

      {query.isError ? (
        <ErrorState
          title="Could not load sources"
          message={query.error instanceof Error ? query.error.message : "Unavailable."}
          onRetry={() => void query.refetch()}
        />
      ) : query.isLoading ? (
        <LoadingState label="Loading sources" rows={5} />
      ) : !data || data.sources.length === 0 ? (
        <EmptyState
          title="No sources configured"
          description="Nothing is registered to ingest from. Migration V5 seeds the synthetic feeds."
        />
      ) : (
        <>
          <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-3">
            <Tile label="Registered" value={String(data.total)} />
            <Tile label="Active" value={String(data.active)} />
            <Tile
              label="Silent"
              value={String(data.silent)}
              // Only coloured when it is non-zero. A permanent red "0" trains
              // the reader to stop looking at it.
              level={data.silent > 0 ? "high" : undefined}
              note={data.silent > 0 ? `active, nothing in ${data.windowHours}h` : "all active feeds delivering"}
            />
          </div>

          <Card className="overflow-hidden">
            <CardHeader
              title="Feeds"
              description={`Row counts are measured over the last ${data.windowHours} hours from the event tables, not read from each source's own timestamp.`}
            />
            <div className="overflow-x-auto">
              <table className="w-full text-left text-[12px]">
                <thead>
                  <tr className="border-b border-line-subtle text-[11px] text-content-tertiary">
                    <th scope="col" className="px-4 py-2.5 font-medium">Source</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Type</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Mode</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Provenance</th>
                    <th scope="col" className="px-4 py-2.5 font-medium">Status</th>
                    <th scope="col" className="px-4 py-2.5 text-right font-medium">
                      Rows / {data.windowHours}h
                    </th>
                    <th scope="col" className="px-4 py-2.5 text-right font-medium">Last delivery</th>
                  </tr>
                </thead>
                <tbody>
                  {data.sources.map((source) => (
                    <Row key={source.id} source={source} />
                  ))}
                </tbody>
              </table>
            </div>
          </Card>

          <Attributions sources={data.sources} />

          <p className="text-[11px] leading-relaxed text-content-tertiary">
            Traffic, weather, incidents and city events are synthetic, and labelled so. That is
            the platform&rsquo;s design rather than a gap: it runs with no external API at all, so
            the pipeline, the models and the dashboard can be exercised end to end without
            depending on a third party&rsquo;s availability or terms. Air quality is the exception
            — where a real feed covers a zone it replaces the generated reading outright, never
            averages with it, and every figure in the product carries which of the three it is.
          </p>
        </>
      )}
    </div>
  );
}

function Row({ source }: { source: DataSourceSummary }) {
  const paused = source.status !== "ACTIVE";
  return (
    <tr className="border-b border-line-subtle last:border-0 hover:bg-surface-hover">
      <td className="px-4 py-2.5">
        <span className="block text-content-primary">{source.name}</span>
        <span className="text-[10px] text-content-tertiary">{source.code}</span>
      </td>
      <td className="px-4 py-2.5 text-content-secondary">
        {TYPE_LABEL[source.sourceType] ?? source.sourceType}
      </td>
      <td className="px-4 py-2.5">
        <span className="text-content-secondary">{source.ingestionMode.toLowerCase()}</span>
      </td>
      <td className="px-4 py-2.5">
        <Badge level={provenanceLevel(source.provenance)} title={PROVENANCE_DETAIL[source.provenance]}>
          {PROVENANCE_LABEL[source.provenance]}
        </Badge>
      </td>
      <td className="px-4 py-2.5">
        {source.silent ? (
          // The distinction the page exists for: running by configuration,
          // not running in fact.
          <Badge level="high">Silent</Badge>
        ) : paused ? (
          <Badge level="neutral">{source.status.toLowerCase()}</Badge>
        ) : (
          <Badge level="normal">Delivering</Badge>
        )}
      </td>
      <td className={cn("px-4 py-2.5 text-right tabular",
        source.rowsInWindow === 0 ? "text-content-disabled" : "text-content-primary")}>
        {source.rowsInWindow.toLocaleString()}
      </td>
      <td className="px-4 py-2.5 text-right text-content-secondary">
        {source.lastIngestedAt === null ? (
          // "never" and "a long time ago" are different problems.
          <span className="text-content-disabled">never</span>
        ) : (
          relativeAge(source.secondsSinceLastIngest)
        )}
      </td>
    </tr>
  );
}

/**
 * Credits the real feeds' licences require.
 *
 * Not optional and not decorative. WAQI's terms make attribution to the project
 * and to the originating agency mandatory, and Open-Meteo's data is CC BY 4.0,
 * so a deployment that displayed these readings without this block would be
 * using them outside the terms it accepted by fetching them.
 *
 * Rendered from what each source reports rather than from a constant here: the
 * originating agency differs per station, so the honest list is the one the
 * ingester wrote from the responses behind the readings actually held. A feed
 * that has never delivered contributes nothing, which is why this can be empty.
 */
function Attributions({ sources }: { sources: DataSourceSummary[] }) {
  const credits = new Map<string, { name: string; url: string }>();
  for (const source of sources) {
    for (const credit of source.attribution ?? []) {
      credits.set(`${credit.name}|${credit.url}`, credit);
    }
  }
  if (credits.size === 0) return null;

  return (
    <Card>
      <CardHeader
        title="Data attribution"
        description="Required by the licences the real feeds are used under."
      />
      <ul className="flex flex-wrap gap-x-4 gap-y-1.5 px-5 pb-4 text-[11px] text-content-tertiary">
        {[...credits.values()].map((credit) => (
          <li key={`${credit.name}|${credit.url}`}>
            {credit.url ? (
              <a
                href={credit.url}
                target="_blank"
                rel="noopener noreferrer"
                className="underline decoration-line-default underline-offset-2 hover:text-content-secondary"
              >
                {credit.name}
              </a>
            ) : (
              // Named without a link. Rendered as text rather than a dead
              // anchor, which would look like a broken page.
              credit.name
            )}
          </li>
        ))}
      </ul>
    </Card>
  );
}

function Tile({
  label,
  value,
  note,
  level,
}: {
  label: string;
  value: string;
  note?: string;
  level?: "high";
}) {
  return (
    <div className="bg-surface-raised px-5 py-4">
      <div className="text-[10px] font-medium uppercase tracking-[0.08em] text-content-tertiary">
        {label}
      </div>
      <div
        className={cn(
          "mt-1 tabular text-[22px] font-semibold leading-none",
          level === "high" ? "text-status-high" : "text-content-primary",
        )}
      >
        {value}
      </div>
      {note && <div className="mt-1.5 text-[11px] text-content-tertiary">{note}</div>}
    </div>
  );
}
