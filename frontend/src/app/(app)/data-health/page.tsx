"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import {
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHeader,
  cn,
} from "@/components/ui";
import { dataSourceApi } from "@/lib/api/endpoints";
import type { StageQuality } from "@/lib/api/types";

/**
 * Whether the pipeline behind the numbers is healthy.
 *
 * A different question from Data Sources, which asks whether each feed is
 * delivering. This asks whether what arrived was any good: received against
 * kept, refused, duplicated, late, and how far behind the worst record was.
 *
 * The counts are written by the loader as it ran. A validity ratio derived
 * afterwards from the curated tables cannot see a record that was rejected —
 * it would always read 100%, which is the most reassuring possible way to be
 * useless.
 *
 * The page's job is to let a reader distrust the dashboard when they should.
 * Every other screen presents conclusions; this one shows the evidence those
 * conclusions rest on, including its own gaps.
 */

/**
 * The four stages, named as the schema names them.
 *
 * INGEST, VALIDATE, AGGREGATE, LOAD — the check constraint on
 * data_quality_metrics is the authority, and it rejected an invented
 * "TRANSFORM" rather than storing a name nothing else would recognise.
 */
const STAGE_LABEL: Record<string, string> = {
  INGEST: "Ingest",
  VALIDATE: "Validation",
  AGGREGATE: "Aggregation",
  LOAD: "Load",
};

const STAGE_NOTE: Record<string, string> = {
  INGEST: "Reading events off the source before anything is examined.",
  VALIDATE: "Schema, physical bounds and lateness checks before anything is stored.",
  AGGREGATE: "Folding events into curated five-minute windows per zone.",
  LOAD: "Writing raw events. Duplicates are recognised and skipped, not refused.",
};

/** Stages the pipeline has, so a missing one can be named rather than omitted. */
const ALL_STAGES = ["INGEST", "VALIDATE", "AGGREGATE", "LOAD"] as const;

function formatCount(n: number): string {
  return n.toLocaleString();
}

export default function DataHealthPage() {
  const query = useQuery({
    queryKey: ["pipeline-health"],
    queryFn: () => dataSourceApi.health(),
    refetchInterval: 60_000,
  });

  const data = query.data;
  const instrumented = new Set((data?.stages ?? []).map((s) => s.stage));
  const uninstrumented = ALL_STAGES.filter((s) => !instrumented.has(s));

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Data Health"
        subtitle={<>What the pipeline received against what it kept. The evidence behind every other screen.</>}
      />

      {query.isError ? (
        <ErrorState
          title="Could not load pipeline health"
          message={query.error instanceof Error ? query.error.message : "Unavailable."}
          onRetry={() => void query.refetch()}
        />
      ) : query.isLoading ? (
        <LoadingState label="Loading pipeline health" rows={5} />
      ) : !data ? (
        <EmptyState title="No health data" description="The pipeline has not reported yet." />
      ) : (
        <>
          <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-3">
            <Tile
              label="Refused"
              value={formatCount(data.deadLettered)}
              tone={data.deadLettered > 0 ? "high" : undefined}
              note={
                data.deadLettered > 0
                  ? `set aside in the last ${data.windowHours}h`
                  : "nothing rejected in this window"
              }
            />
            <Tile
              label="Silent sources"
              value={`${data.silentSources} of ${data.totalSources}`}
              tone={data.silentSources > 0 ? "high" : undefined}
              note={
                data.silentSources > 0
                  ? "active, delivering nothing"
                  : "every active feed is delivering"
              }
            />
            <Tile
              label="Instrumented stages"
              value={`${data.stages.length} of ${ALL_STAGES.length}`}
              tone={uninstrumented.length > 0 ? "moderate" : undefined}
              note={
                uninstrumented.length > 0
                  ? `${uninstrumented.map((s) => STAGE_LABEL[s]).join(", ")} not measured`
                  : "every stage reports quality"
              }
            />
          </div>

          <Card className="overflow-hidden">
            <CardHeader
              title="Pipeline stages"
              description={`Counted by the loader as it ran, over the last ${data.windowHours} hours.`}
            />
            {data.stages.length === 0 ? (
              <EmptyState
                title="No stage reported"
                description="The pipeline has written no quality metrics in this window. That is a gap in the instrumentation, not evidence that nothing was processed."
              />
            ) : (
              <div className="divide-y divide-line-subtle">
                {data.stages.map((stage) => (
                  <StageRow key={stage.stage} stage={stage} />
                ))}
              </div>
            )}
          </Card>

          {uninstrumented.length > 0 && (
            // Named, not omitted. A page listing only what it measures reads as
            // a complete picture, and the missing stages are exactly where a
            // problem would go unseen.
            <Card>
              <CardHeader
                title="Not measured"
                description="These stages run but write no quality metrics, so this page cannot speak for them."
              />
              <ul className="space-y-2 px-5 pb-4 pt-1">
                {uninstrumented.map((stage) => (
                  <li key={stage} className="flex items-start gap-2.5">
                    <span
                      aria-hidden="true"
                      className="mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full bg-content-disabled"
                    />
                    <div>
                      <span className="text-[12.5px] text-content-secondary">
                        {STAGE_LABEL[stage]}
                      </span>
                      <p className="text-[11px] text-content-tertiary">{STAGE_NOTE[stage]}</p>
                    </div>
                  </li>
                ))}
              </ul>
            </Card>
          )}

          <p className="text-[11.5px] leading-relaxed text-content-tertiary">
            Validity is counted by the loader as records pass through it, not derived afterwards
            from the curated tables — a record that was rejected is not there to be counted, so a
            ratio computed after the fact would always read 100%. Feed-level delivery is on{" "}
            <Link href="/data-sources" className="text-accent hover:underline">
              Data Sources
            </Link>
            .
          </p>
        </>
      )}
    </div>
  );
}

function StageRow({ stage }: { stage: StageQuality }) {
  const ratio = stage.validityRatio === null ? null : Number(stage.validityRatio);

  /*
   * Colour follows refusals, not the ratio.
   *
   * At LOAD the writers skip an event already stored, so re-running a file
   * reports 305 offered and 19 kept — a ratio of 6% that is not a fault at all.
   * Colouring by ratio would paint a correct, idempotent re-ingestion bright red
   * and teach the reader to ignore the colour. A record that was *refused* is
   * the thing worth alarming about, and a late one is worth noting.
   */
  const refused = stage.recordsRejected;
  const late = stage.recordsLate;
  const clean = refused === 0 && late === 0;

  // "Valid" is the wrong word at LOAD, where the shortfall is recognition of
  // something already held rather than a judgement about the record.
  const ratioLabel = stage.stage === "LOAD" ? "% newly stored" : "% valid";

  return (
    <section className="px-5 py-4">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <div>
          <h3 className="text-[13.5px] font-medium text-content-primary">
            {STAGE_LABEL[stage.stage] ?? stage.stage}
          </h3>
          <p className="mt-0.5 text-[11px] text-content-tertiary">
            {STAGE_NOTE[stage.stage] ?? `${stage.windows} reporting windows`}
          </p>
        </div>
        <div className="text-right">
          {ratio === null ? (
            // Undefined, not zero. Zero would say "nothing was valid", which is
            // a different and far more alarming claim than "nothing arrived".
            <span className="text-[12px] text-content-disabled">nothing received</span>
          ) : (
            <>
              <span
                className={cn(
                  "tabular text-[20px] font-semibold leading-none",
                  refused > 0
                    ? "text-status-high"
                    : late > 0
                      ? "text-status-moderate"
                      : "text-content-primary",
                )}
              >
                {(ratio * 100).toFixed(ratio >= 0.999 ? 1 : 1)}
              </span>
              <span className="ml-1 text-[11px] text-content-tertiary">{ratioLabel}</span>
            </>
          )}
        </div>
      </div>

      <div className="mt-3 grid grid-cols-2 gap-x-5 gap-y-2 sm:grid-cols-5">
        <Figure label="Received" value={formatCount(stage.recordsReceived)} />
        <Figure label="Kept" value={formatCount(stage.recordsValid)} />
        <Figure
          label="Rejected"
          value={formatCount(stage.recordsRejected)}
          tone={stage.recordsRejected > 0 ? "high" : undefined}
        />
        <Figure
          label={stage.stage === "LOAD" ? "Already held" : "Duplicate"}
          value={formatCount(stage.recordsDuplicate)}
        />
        <Figure
          label="Late"
          value={formatCount(stage.recordsLate)}
          tone={stage.recordsLate > 0 ? "moderate" : undefined}
        />
      </div>

      <p className="mt-3 text-[10.5px] text-content-tertiary">
        {stage.windows} {stage.windows === 1 ? "window" : "windows"} reported
        {stage.maxLagSeconds !== null ? (
          <> · worst lag {stage.maxLagSeconds}s</>
        ) : (
          // Absent, not zero. A pipeline that never measured lag has not proved
          // it has none.
          <> · lag not recorded</>
        )}
        {clean && stage.recordsReceived > 0 && (
          <> · nothing refused or late</>
        )}
        {stage.recordsDuplicate > 0 && (
          // Named as what it is. At LOAD this is the writers recognising an
          // event they already hold, which is the pipeline being idempotent
          // rather than the pipeline failing.
          <>
            {" "}· {stage.recordsDuplicate.toLocaleString()} already held
            {stage.stage === "LOAD" ? " and skipped" : ""}
          </>
        )}
      </p>
    </section>
  );
}

function Figure({ label, value, tone }: { label: string; value: string; tone?: "high" | "moderate" }) {
  return (
    <div>
      <div className="text-[10px] font-medium uppercase tracking-[0.08em] text-content-disabled">
        {label}
      </div>
      <div
        className={cn(
          "mt-0.5 tabular text-[14px] font-medium",
          tone === "high"
            ? "text-status-high"
            : tone === "moderate"
              ? "text-status-moderate"
              : "text-content-secondary",
        )}
      >
        {value}
      </div>
    </div>
  );
}

function Tile({
  label,
  value,
  note,
  tone,
}: {
  label: string;
  value: string;
  note?: string;
  tone?: "high" | "moderate";
}) {
  return (
    <div className="bg-surface-raised px-5 py-4">
      <div className="text-[10px] font-medium uppercase tracking-[0.08em] text-content-tertiary">
        {label}
      </div>
      <div
        className={cn(
          "mt-1 tabular text-[22px] font-semibold leading-none",
          tone === "high"
            ? "text-status-high"
            : tone === "moderate"
              ? "text-status-moderate"
              : "text-content-primary",
        )}
      >
        {value}
      </div>
      {note && <div className="mt-1.5 text-[11px] text-content-tertiary">{note}</div>}
    </div>
  );
}
