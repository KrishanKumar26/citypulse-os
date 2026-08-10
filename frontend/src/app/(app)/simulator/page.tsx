"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";

import {
  Badge,
  Button,
  Card,
  CardHeader,
  DemoDataBadge,
  EmptyState,
  ErrorState,
  LoadingState,
  Metric,
  PageHeader,
  cn,
} from "@/components/ui";
import { geoApi, simulationApi } from "@/lib/api/endpoints";
import type {
  ConditionLevel,
  ImpactSource,
  RunScenarioRequest,
  SimulationDetail,
  SimulationSummary,
  ZoneImpact,
} from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";

/**
 * What-If Simulator (PRD §14).
 *
 * Two things this page must never let slip. First, that the output is a
 * *simulation* — a stated model's answer, not a measurement and not a
 * prediction. Second, which effects the scenario stated and which the engine
 * inferred: a closed road is an input, a neighbouring zone's congestion is a
 * guess from proximity, and acting on the two deserves different confidence.
 */

const LEVEL_BADGE: Record<ConditionLevel, "normal" | "moderate" | "high" | "critical"> = {
  NORMAL: "normal",
  MODERATE: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
};

const SOURCE_LABEL: Record<ImpactSource, string> = {
  DIRECT: "Stated",
  SPILLOVER: "Inferred",
  CITYWIDE: "City-wide",
};

const EVENT_TYPES = ["CONCERT", "SPORTS", "FESTIVAL", "CONFERENCE", "PARADE", "MARATHON"];

function pct(value: string | null): string {
  if (value === null) return "—";
  const n = Number(value);
  return `${n > 0 ? "+" : ""}${n.toFixed(1)}%`;
}

export default function SimulatorPage() {
  const { city } = useSelectedCity();

  const [name, setName] = useState("Heavy rain during evening peak");
  const [rain, setRain] = useState<number | "">(15);
  const [eventZone, setEventZone] = useState("");
  const [eventType, setEventType] = useState("CONCERT");
  const [attendance, setAttendance] = useState<number | "">("");
  const [closureZone, setClosureZone] = useState("");
  const [capacityReduction, setCapacityReduction] = useState<number | "">("");
  const [transitDisruption, setTransitDisruption] = useState<number | "">("");
  const [volumeChange, setVolumeChange] = useState<number | "">("");

  const zonesQuery = useQuery({
    queryKey: ["zones", city?.id],
    queryFn: () => geoApi.listZones(city!.id, true),
    enabled: Boolean(city),
  });

  const historyQuery = useQuery({
    queryKey: ["simulations", city?.slug],
    queryFn: () => simulationApi.history(city!.slug),
    enabled: Boolean(city),
  });

  const run = useMutation({
    mutationFn: (request: RunScenarioRequest) => simulationApi.run(request),
    onSuccess: () => void historyQuery.refetch(),
  });

  const load = useMutation({
    mutationFn: (id: string) => simulationApi.get(id),
  });

  const result = load.data ?? run.data ?? null;
  const zones = zonesQuery.data ?? [];

  const submit = () => {
    if (!city) return;
    load.reset();

    const request: RunScenarioRequest = { name, citySlug: city.slug };
    if (rain !== "") request.weather = { rainIntensityMmH: Number(rain) };
    if (eventZone && attendance !== "") {
      request.event = {
        zoneCode: eventZone,
        eventType,
        expectedAttendance: Number(attendance),
        startsInHours: 0,
        durationHours: 4,
      };
    }
    if (closureZone && capacityReduction !== "") {
      request.infrastructure = {
        closedRoadZoneCodes: [closureZone],
        capacityReductionPct: Number(capacityReduction),
      };
    }
    if (transitDisruption !== "") {
      request.infrastructure = {
        ...(request.infrastructure ?? {}),
        transitDisruptionPct: Number(transitDisruption),
      };
    }
    if (volumeChange !== "") request.traffic = { volumeChangePct: Number(volumeChange) };

    run.mutate(request);
  };

  if (!city) {
    return <LoadingState label="Loading city" rows={4} />;
  }

  const error = run.isError
    ? run.error instanceof Error
      ? run.error.message
      : "The scenario could not be run."
    : null;

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="What-If Simulator"
        subtitle={`${city.name} · hypothetical scenarios run against currently observed conditions`}
      />

      {/*
        Stated once, prominently. Everything below is a model's answer, not a
        measurement and not a forecast — and a simulator's output is the easiest
        thing in this product to mistake for either.
      */}
      <div className="rounded-lg border border-line-subtle bg-surface-overlay px-4 py-3">
        <p className="text-[13px] leading-relaxed text-content-secondary">
          <span className="font-medium text-content-primary">These results are simulated.</span> The
          engine starts from conditions the city really is in and applies a stated model of how
          rain, events, closures and traffic volume interact. It is not a prediction of what will
          happen; its assumptions are documented and unit tested rather than tuned to look
          convincing.
        </p>
      </div>

      {error && <ErrorState title="Could not run the scenario" message={error} />}

      <div className="grid gap-5 lg:grid-cols-3">
        <Card className="lg:col-span-1">
          <CardHeader title="Scenario" description="Leave a field blank to exclude it." />
          <div className="space-y-4 px-5 py-4">
            <Field label="Name">
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                className="w-full rounded-md border border-line-subtle bg-surface-raised px-2.5 py-1.5 text-[13px]"
              />
            </Field>

            <Group title="Weather">
              <Field label="Rain (mm/h)" hint="0–50">
                <NumberInput value={rain} onChange={setRain} min={0} max={50} />
              </Field>
            </Group>

            <Group title="Event">
              <Field label="Zone">
                <select
                  value={eventZone}
                  onChange={(e) => setEventZone(e.target.value)}
                  className="w-full rounded-md border border-line-subtle bg-surface-raised px-2.5 py-1.5 text-[13px]"
                >
                  <option value="">None</option>
                  {zones.map((z) => (
                    <option key={z.id} value={z.code}>
                      {z.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Type">
                <select
                  value={eventType}
                  onChange={(e) => setEventType(e.target.value)}
                  className="w-full rounded-md border border-line-subtle bg-surface-raised px-2.5 py-1.5 text-[13px]"
                >
                  {EVENT_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t.charAt(0) + t.slice(1).toLowerCase()}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Attendance">
                <NumberInput value={attendance} onChange={setAttendance} min={0} max={500000} />
              </Field>
            </Group>

            <Group title="Infrastructure">
              <Field label="Zone with closure">
                <select
                  value={closureZone}
                  onChange={(e) => setClosureZone(e.target.value)}
                  className="w-full rounded-md border border-line-subtle bg-surface-raised px-2.5 py-1.5 text-[13px]"
                >
                  <option value="">None</option>
                  {zones.map((z) => (
                    <option key={z.id} value={z.code}>
                      {z.name}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="Capacity removed (%)" hint="0–90">
                <NumberInput
                  value={capacityReduction}
                  onChange={setCapacityReduction}
                  min={0}
                  max={90}
                />
              </Field>
              <Field label="Transit out of service (%)" hint="0–100">
                <NumberInput
                  value={transitDisruption}
                  onChange={setTransitDisruption}
                  min={0}
                  max={100}
                />
              </Field>
            </Group>

            <Group title="Traffic">
              <Field label="Volume change (%)" hint="−80 to +300">
                <NumberInput value={volumeChange} onChange={setVolumeChange} min={-80} max={300} />
              </Field>
            </Group>

            <Button onClick={submit} loading={run.isPending} className="w-full">
              Run scenario
            </Button>
          </div>
        </Card>

        <div className="space-y-5 lg:col-span-2">
          {run.isPending ? (
            <Card>
              <LoadingState label="Running scenario" rows={4} />
            </Card>
          ) : result ? (
            <ResultView result={result} />
          ) : (
            <Card>
              <EmptyState
                title="No scenario run yet"
                description="Set at least one input on the left and run it. Results compare against the city's most recent observed window."
              />
            </Card>
          )}

          <HistoryPanel
            items={historyQuery.data?.items ?? []}
            loading={historyQuery.isLoading}
            onLoad={(id) => {
              run.reset();
              load.mutate(id);
            }}
          />
        </div>
      </div>
    </div>
  );
}

/**
 * Which direction is the bad one, per headline figure.
 *
 * <p>Parking is the odd one out and the reason this table exists rather than a
 * single rule. The engine returns *availability*, negated from vehicle demand —
 * "more vehicles means less availability, hence the negation" — so a positive
 * parking figure is the only positive number on this row that is good news.
 * Colouring the four alike would paint the one improvement in the set red.
 */
const HEADLINE: Array<{
  label: string;
  read: (r: SimulationDetail) => string | null;
  format: (n: number) => string;
  higherIsWorse: boolean;
}> = [
  { label: "Traffic", read: (r) => r.trafficChangePct, format: signedPct, higherIsWorse: true },
  { label: "Crowd", read: (r) => r.crowdChangePct, format: signedPct, higherIsWorse: true },
  {
    // Named for what it measures. "Parking" alone leaves a reader to guess
    // whether more is congestion or capacity.
    label: "Parking availability",
    read: (r) => r.parkingChangePct,
    format: signedPct,
    higherIsWorse: false,
  },
  {
    label: "Delay",
    read: (r) => r.delayChangeMin,
    format: (n) => `${n > 0 ? "+" : ""}${n.toFixed(1)} min`,
    higherIsWorse: true,
  },
];

function signedPct(n: number): string {
  return `${n > 0 ? "+" : ""}${n.toFixed(1)}%`;
}

/** Null unless the figure moved: zero change is neither good nor bad. */
function toneOf(n: number, higherIsWorse: boolean): "normal" | "high" | null {
  if (n === 0) return null;
  return n > 0 === higherIsWorse ? "high" : "normal";
}

function ResultView({ result }: { result: SimulationDetail }) {
  const baselineRisk = result.baselineRisk === null ? null : Number(result.baselineRisk);
  const simulatedRisk = result.simulatedRisk === null ? null : Number(result.simulatedRisk);
  const riskDelta =
    baselineRisk === null || simulatedRisk === null ? null : simulatedRisk - baselineRisk;

  return (
    <>
      <Card className="overflow-hidden">
        <CardHeader
          title={result.name}
          description={`From the window at ${new Date(result.baselineWindow).toLocaleString()} · engine ${result.engineVersion}`}
          action={
            <div className="flex items-center gap-1.5">
              <Badge level="info">SIMULATED</Badge>
              {/*
                Two separate claims, so two separate labels. "Simulated" means
                the engine computed it; "demo data" means the conditions it
                started from were generated. A result can be one without the
                other, and collapsing them would let synthetic input pass as
                real once the modelling was understood.
              */}
              {result.demoData && <DemoDataBadge />}
            </div>
          }
        />

        {/* Composite risk leads, because it is the one figure that answers "is
            this scenario better or worse". The four component changes sit
            beneath it — they explain the movement, they are not the verdict. */}
        <div className="grid gap-px bg-line-subtle sm:grid-cols-[minmax(0,1.15fr)_minmax(0,2fr)]">
          <div className="bg-surface-raised px-5 py-4">
            <Metric
              label="Overall risk"
              emphasis="hero"
              value={riskDelta === null ? null : `${riskDelta > 0 ? "+" : ""}${riskDelta.toFixed(0)}`}
              level={riskDelta === null ? null : toneOf(riskDelta, true)}
              absenceReason="No baseline risk"
              note={
                baselineRisk === null || simulatedRisk === null
                  ? undefined
                  : `${baselineRisk.toFixed(0)} → ${simulatedRisk.toFixed(0)} out of 100`
              }
            />
            <RiskShift baseline={baselineRisk} simulated={simulatedRisk} />
          </div>

          <div className="grid gap-px bg-line-subtle sm:grid-cols-2">
            {HEADLINE.map((metric) => {
              const raw = metric.read(result);
              const n = raw === null ? null : Number(raw);
              return (
                <div key={metric.label} className="bg-surface-raised px-4 py-3.5">
                  <Metric
                    label={metric.label}
                    value={n === null ? null : metric.format(n)}
                    level={n === null ? null : toneOf(n, metric.higherIsWorse)}
                    absenceReason="Not modelled"
                  />
                </div>
              );
            })}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 border-t border-line-subtle px-5 py-3 text-[13px]">
          <span className="text-content-tertiary">
            {result.zonesAffected} {result.zonesAffected === 1 ? "zone" : "zones"} affected
          </span>
          {result.computedMs !== null && (
            <span className="text-[11px] text-content-tertiary">
              computed in {result.computedMs} ms
            </span>
          )}
        </div>
      </Card>

      {result.recommendations.length > 0 && (
        <Card>
          <CardHeader
            title="Recommended actions"
            description="Each names the zone and the reason that produced it."
          />
          <ul className="divide-y divide-line-subtle">
            {result.recommendations.map((rec, index) => (
              <li key={`${rec.action}-${index}`} className="px-5 py-3">
                <div className="flex items-start gap-2.5">
                  <Badge
                    level={
                      rec.priority === "HIGH"
                        ? "critical"
                        : rec.priority === "MEDIUM"
                          ? "high"
                          : "normal"
                    }
                  >
                    {rec.priority}
                  </Badge>
                  <div className="min-w-0">
                    <div className="text-[13px] text-content-primary">{rec.action}</div>
                    <div className="mt-0.5 text-[12px] text-content-tertiary">{rec.reason}</div>
                  </div>
                </div>
              </li>
            ))}
          </ul>
        </Card>
      )}

      <Card className="overflow-hidden">
        <CardHeader
          title="Zone impact"
          description="Before and after, worst first. 'Inferred' effects come from proximity, not the road network."
        />
        <ZoneImpactTable zones={result.zones} />
      </Card>
    </>
  );
}

function ZoneImpactTable({ zones }: { zones: ZoneImpact[] }) {
  if (zones.length === 0) {
    return (
      <EmptyState title="No zones affected" description="This scenario changed nothing measurable." />
    );
  }

  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-[13px]">
        <thead>
          <tr className="border-b border-line-subtle text-[12px] text-content-tertiary">
            <th scope="col" className="px-5 py-2.5 font-medium">Zone</th>
            <th scope="col" className="px-5 py-2.5 font-medium">Condition</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">Occupancy</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">Speed</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">Risk</th>
            <th scope="col" className="px-5 py-2.5 text-right font-medium">Delay</th>
            <th scope="col" className="px-5 py-2.5 font-medium">Basis</th>
          </tr>
        </thead>
        <tbody>
          {zones.map((zone) => (
            <tr key={zone.zoneId} className="border-b border-line-subtle last:border-0">
              <td className="px-5 py-2.5">
                <div className="font-medium">{zone.zoneName}</div>
                <div className="text-[11px] text-content-tertiary">{zone.zoneCode}</div>
              </td>
              <td className="px-5 py-2.5">
                <div className="flex items-center gap-1.5">
                  {zone.baselineCongestion && (
                    <span className="text-[11px] text-content-tertiary">
                      {zone.baselineCongestion}
                    </span>
                  )}
                  <span className="text-content-tertiary">→</span>
                  {zone.simulatedCongestion && (
                    <Badge level={LEVEL_BADGE[zone.simulatedCongestion]}>
                      {zone.simulatedCongestion}
                    </Badge>
                  )}
                </div>
              </td>
              <td className="px-5 py-2.5 text-right tabular">
                <span className="text-content-tertiary">
                  {zone.baselineOccupancy ? Number(zone.baselineOccupancy).toFixed(2) : "—"}
                </span>{" "}
                →{" "}
                <span className="font-medium">
                  {zone.simulatedOccupancy ? Number(zone.simulatedOccupancy).toFixed(2) : "—"}
                </span>
              </td>
              <td className="px-5 py-2.5 text-right tabular">
                <span className="text-content-tertiary">
                  {zone.baselineSpeedKph ? Number(zone.baselineSpeedKph).toFixed(0) : "—"}
                </span>{" "}
                →{" "}
                <span className="font-medium">
                  {zone.simulatedSpeedKph ? Number(zone.simulatedSpeedKph).toFixed(0) : "—"}
                </span>
              </td>
              <td className="px-5 py-2.5 text-right tabular">
                <span className="text-content-tertiary">
                  {zone.baselineRiskScore ? Number(zone.baselineRiskScore).toFixed(0) : "—"}
                </span>{" "}
                →{" "}
                <span className="font-medium">
                  {zone.simulatedRiskScore ? Number(zone.simulatedRiskScore).toFixed(0) : "—"}
                </span>
              </td>
              <td className="px-5 py-2.5 text-right tabular">
                {zone.delayChangeMin === null
                  ? "—"
                  : `${Number(zone.delayChangeMin) > 0 ? "+" : ""}${Number(zone.delayChangeMin).toFixed(1)}m`}
              </td>
              <td className="px-5 py-2.5">
                <span
                  className={`text-[11px] ${
                    zone.impactSource === "SPILLOVER"
                      ? "text-status-moderate"
                      : "text-content-tertiary"
                  }`}
                  title={
                    zone.impactSource === "SPILLOVER"
                      ? "Inferred from straight-line proximity, not the road network"
                      : undefined
                  }
                >
                  {SOURCE_LABEL[zone.impactSource]}
                </span>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function HistoryPanel({
  items,
  loading,
  onLoad,
}: {
  items: SimulationSummary[];
  loading: boolean;
  onLoad: (id: string) => void;
}) {
  return (
    <Card className="overflow-hidden">
      <CardHeader
        title="Saved scenarios"
        description="Results persist and reload with the baseline they were computed from."
      />
      {loading ? (
        <LoadingState label="Loading history" rows={3} />
      ) : items.length === 0 ? (
        <EmptyState title="No saved scenarios" description="Scenarios you run are stored here." />
      ) : (
        <ul className="divide-y divide-line-subtle">
          {items.map((item) => (
            <li key={item.id}>
              <button
                type="button"
                onClick={() => onLoad(item.id)}
                className="flex w-full items-center justify-between gap-4 px-5 py-2.5 text-left transition-colors hover:bg-surface-hover"
              >
                <div className="min-w-0">
                  <div className="truncate text-[13px] text-content-primary">{item.name}</div>
                  <div className="text-[11px] text-content-tertiary">
                    {new Date(item.createdAt).toLocaleString()} · {item.zonesAffected} zones
                  </div>
                </div>
                <span className="shrink-0 text-[13px] tabular text-content-secondary">
                  {pct(item.trafficChangePct)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </Card>
  );
}

function Group({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <fieldset className="space-y-2 border-t border-line-subtle pt-3">
      <legend className="text-[11px] font-medium uppercase tracking-wide text-content-tertiary">
        {title}
      </legend>
      {children}
    </fieldset>
  );
}

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1 flex items-baseline justify-between text-[12px] text-content-secondary">
        {label}
        {hint && <span className="text-[11px] text-content-tertiary">{hint}</span>}
      </span>
      {children}
    </label>
  );
}

function NumberInput({
  value,
  onChange,
  min,
  max,
}: {
  value: number | "";
  onChange: (value: number | "") => void;
  min: number;
  max: number;
}) {
  return (
    <input
      type="number"
      value={value}
      min={min}
      max={max}
      onChange={(e) => onChange(e.target.value === "" ? "" : Number(e.target.value))}
      className="w-full rounded-md border border-line-subtle bg-surface-raised px-2.5 py-1.5 text-[13px] tabular"
    />
  );
}

/**
 * Baseline and simulated risk as two marks on one 0–100 track.
 *
 * <p>A pair of numbers with an arrow between them makes the reader do the
 * subtraction and gives no sense of scale: 42 → 58 and 82 → 98 are the same
 * arithmetic and not the same situation. On a shared track the second is
 * visibly near the top.
 */
function RiskShift({ baseline, simulated }: { baseline: number | null; simulated: number | null }) {
  if (baseline === null || simulated === null) return null;

  const clamp = (n: number) => Math.max(0, Math.min(100, n));
  const from = clamp(baseline);
  const to = clamp(simulated);
  const worse = to > from;

  return (
    <div className="mt-3">
      <div className="relative h-1.5 rounded-full bg-surface-hover">
        {/* The span between the two, coloured by direction of travel. */}
        <div
          className={cn(
            "absolute inset-y-0 rounded-full",
            worse ? "bg-status-high" : "bg-status-normal",
          )}
          style={{ left: `${Math.min(from, to)}%`, width: `${Math.abs(to - from)}%` }}
        />
        {/* Two-pixel surface ring so the marks stay legible where they overlap. */}
        <span
          aria-hidden="true"
          className="absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-surface-raised bg-content-tertiary"
          style={{ left: `${from}%` }}
        />
        <span
          aria-hidden="true"
          className={cn(
            "absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-surface-raised",
            worse ? "bg-status-high" : "bg-status-normal",
          )}
          style={{ left: `${to}%` }}
        />
      </div>
      <div className="mt-1.5 flex justify-between text-[10px] text-content-tertiary">
        <span>0</span>
        <span>now {from.toFixed(0)} · scenario {to.toFixed(0)}</span>
        <span>100</span>
      </div>
    </div>
  );
}
