"use client";

import { useQuery } from "@tanstack/react-query";
import dynamic from "next/dynamic";
import { useMemo, useState } from "react";
import {
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHeader,
  Skeleton,
  cn,
} from "@/components/ui";
import {
  CONDITION_COLORS,
  MAP_LAYERS,
  NO_DATA_COLOR,
  type MapLayer,
} from "@/components/map/ZoneMap";
import { geoApi, liveApi } from "@/lib/api/endpoints";
import type { Zone, ZoneCondition } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";
import { useLiveSnapshot } from "@/lib/live/useLiveSnapshot";
import { KpiRow } from "@/components/live/KpiRow";
import { SituationFeed } from "@/components/live/SituationFeed";
import { RiskDistribution, ZoneRiskChart } from "@/components/charts/ZoneRiskChart";
import { ZoneIntelligence } from "@/components/live/ZoneIntelligence";
import { ZoneTable } from "@/components/live/ZoneTable";
import { LiveStatusBar } from "@/components/live/LiveStatusBar";
import { formatNumber } from "@/lib/format";

// Leaflet touches window at import time, so the map is loaded client-side only.
const ZoneMap = dynamic(() => import("@/components/map/ZoneMap"), {
  ssr: false,
  loading: () => <div className="skeleton h-full w-full" />,
});

/**
 * Command Center (PRD §8).
 *
 * Two sources, deliberately kept apart. Geography — zone definitions, road
 * capacity, area coverage — is stable reference data and comes from a plain
 * query. Conditions come from the live stream and change every few seconds.
 *
 * Mixing them into one fetch would mean re-reading static geography on every
 * push, and every figure on the page still traces to a stored row either way:
 * capacity from `zones`, congestion and risk from `zone_metrics`, with the
 * window each reading came from carried alongside it.
 */
export default function CommandCenterPage() {
  const { city } = useSelectedCity();
  const [selectedZone, setSelectedZone] = useState<Zone | null>(null);
  const [layer, setLayer] = useState<MapLayer>("risk");

  const zonesQuery = useQuery({
    queryKey: ["zones", city?.id],
    queryFn: () => geoApi.listZones(city!.id, true),
    enabled: Boolean(city),
  });

  const { snapshot, status, lastEventAt, reconnect } = useLiveSnapshot(city?.slug ?? null);

  // The city's recent series, for the trend beneath each KPI. Separate from the
  // live snapshot because it changes on the pipeline's cadence rather than the
  // stream's, and a failure here must cost a sparkline, not the dashboard.
  const historyQuery = useQuery({
    queryKey: ["city-history", city?.slug],
    queryFn: () => liveApi.cityHistory(city!.slug),
    enabled: Boolean(city),
    staleTime: 5 * 60_000,
  });

  const conditions = useMemo(() => {
    const map = new Map<string, ZoneCondition>();
    snapshot?.zones.forEach((zone) => map.set(zone.zoneId, zone));
    return map;
  }, [snapshot]);

  if (!city) {
    return <LoadingState label="Loading city" rows={4} />;
  }

  const zones = zonesQuery.data ?? [];

  return (
    <div className="p-5">
      <PageHeader
        title={city.name}
        subtitle={`${city.country} · ${city.timezone} · ${city.zoneCount} monitored ${
          city.zoneCount === 1 ? "zone" : "zones"
        }`}
      >
        {/*
          The freshness strip lives in the band: "what am I looking at" and "is
          it current" are one question, not two.

          The city is labelled DEMO DATA once, in the top bar, which is on every
          page and shows at every width. Three identical badges shared this
          screen — top bar, page heading, status strip. Repetition does not make
          a disclosure stronger; it turns it into decoration people stop reading.
        */}
        <LiveStatusBar
          snapshot={snapshot}
          status={status}
          lastEventAt={lastEventAt}
          onReconnect={reconnect}
        />
      </PageHeader>

      {/*
        What needs attention, before what the numbers are.

        The page used to open with twelve metric tiles. An operator arriving
        mid-shift had to read the whole row and compare each figure against a
        sense of normal they were expected to already hold, to work out where to
        look. The feed answers that directly, from anomalies the platform has
        already detected against each zone's own baseline.

        The metrics stay, underneath — they are the context for a situation, not
        a substitute for one.
      */}
      <div className="mb-5">
        <SituationFeed
          city={city}
          conditions={conditions}
          onInvestigate={(zoneId) => {
            const zone = zones.find((z) => z.id === zoneId);
            if (zone) setSelectedZone(zone);
          }}
        />
      </div>

      <KpiRow
        kpis={snapshot?.kpis ?? null}
        history={historyQuery.data ?? null}
        loading={!snapshot}
      />

      <div className="mt-5">
        <CoverageMetrics city={city} zones={zones} loading={zonesQuery.isLoading} />
      </div>

      <div className="mt-5 grid gap-5 lg:grid-cols-3">
        <Card className="overflow-hidden lg:col-span-2">
          <CardHeader
            title="City map"
            description={
              `Marker size is road capacity; colour is ${
                MAP_LAYERS.find((l) => l.id === layer)!.legend.toLowerCase()
              }. Grey means no reading for this layer.`
            }
            action={
              <div className="flex flex-wrap items-center gap-1" role="group" aria-label="Map layer">
                {/* Radius stays capacity on every layer — two channels changing
                    at once would leave a reader unable to tell which moved. */}
                {MAP_LAYERS.map((option) => (
                  <button
                    key={option.id}
                    type="button"
                    onClick={() => setLayer(option.id)}
                    aria-pressed={layer === option.id}
                    className={cn(
                      "rounded-md border px-2 py-1 text-[11px] font-medium transition-colors",
                      layer === option.id
                        ? "border-accent/40 bg-accent-subtle text-accent"
                        : "border-line-default text-content-secondary hover:bg-surface-hover hover:text-content-primary",
                    )}
                  >
                    {option.label}
                  </button>
                ))}
              </div>
            }
          />
          <div className="h-[560px]">
            {zonesQuery.isLoading ? (
              <div className="skeleton h-full w-full" />
            ) : zonesQuery.isError ? (
              <ErrorState
                title="Could not load zones"
                message={
                  zonesQuery.error instanceof Error
                    ? zonesQuery.error.message
                    : "Zone data is unavailable."
                }
                onRetry={() => void zonesQuery.refetch()}
              />
            ) : zones.length === 0 ? (
              <EmptyState
                title="No zones defined"
                description="This city has no active zones yet. Zones are the unit that telemetry, forecasts and risk scores attach to."
              />
            ) : (
              <ZoneMap
                city={city}
                zones={zones}
                layer={layer}
                conditions={conditions}
                selectedZoneId={selectedZone?.id ?? null}
                onSelectZone={setSelectedZone}
              />
            )}
          </div>
          {zones.length > 0 && <ConditionLegend />}
        </Card>

        <div className="space-y-5">
          {/*
            Ranked risk, above the detail panel.
            The right column previously held only "No zone selected" until
            someone clicked the map — a third of the width saying nothing on
            arrival. The one question a reader lands with is which zones are
            worst, and the map answers it only by comparing dot colours across a
            city they may not know.
          */}
          <Card className="overflow-hidden">
            <CardHeader
              title="Risk by zone"
              description="Composite risk on a fixed 0–100 scale, worst first."
            />
            {snapshot ? (
              <>
                <RiskDistribution zones={snapshot.zones} />
                <ZoneRiskChart
                  zones={snapshot.zones}
                  selectedZoneId={selectedZone?.id ?? null}
                  onSelectZone={(zoneId) => {
                    const zone = zones.find((z) => z.id === zoneId);
                    if (zone) setSelectedZone(zone);
                  }}
                />
              </>
            ) : (
              <div className="skeleton m-5 h-48" />
            )}
          </Card>

          <ZoneIntelligence
            zone={selectedZone}
            condition={selectedZone ? conditions.get(selectedZone.id) : undefined}
            cityId={city.id}
          />
        </div>
      </div>

      <div className="mt-5">
        <ZoneTable
          zones={zones}
          conditions={conditions}
          loading={zonesQuery.isLoading}
          selectedZoneId={selectedZone?.id ?? null}
          onSelectZone={setSelectedZone}
        />
      </div>
    </div>
  );
}

function CoverageMetrics({
  city,
  zones,
  loading,
}: {
  city: { population: number | null; areaSqKm: string | null };
  zones: Zone[];
  loading: boolean;
}) {
  const totalCapacity = zones.reduce((sum, zone) => sum + (zone.roadCapacityVph ?? 0), 0);
  const zonePopulation = zones.reduce((sum, zone) => sum + (zone.population ?? 0), 0);
  const monitoredArea = zones.reduce((sum, zone) => sum + Number(zone.areaSqKm ?? 0), 0);
  const cityArea = Number(city.areaSqKm ?? 0);
  const coverage = cityArea > 0 ? (monitoredArea / cityArea) * 100 : null;

  const metrics = [
    { label: "Monitored zones", value: formatNumber(zones.length), unit: "" },
    { label: "Road capacity", value: formatNumber(totalCapacity), unit: "veh/h" },
    { label: "Population covered", value: formatNumber(zonePopulation), unit: "" },
    {
      label: "Area coverage",
      value: coverage !== null ? coverage.toFixed(1) : "—",
      unit: coverage !== null ? "% of city" : "",
    },
  ];

  return (
    <div className="grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle sm:grid-cols-2 lg:grid-cols-4">
      {metrics.map((metric) => (
        <div key={metric.label} className="bg-surface-raised px-4 py-3.5">
          <div className="text-[12px] text-content-tertiary">{metric.label}</div>
          {loading ? (
            <Skeleton className="mt-1.5 h-6 w-20" />
          ) : (
            <div className="mt-1 flex items-baseline gap-1.5">
              <span className="text-xl font-semibold tabular tracking-tight">{metric.value}</span>
              {metric.unit && (
                <span className="text-[12px] text-content-tertiary">{metric.unit}</span>
              )}
            </div>
          )}
        </div>
      ))}
    </div>
  );
}

/**
 * Legend for the map's colour scale.
 *
 * Describes risk bands rather than zone types, because that is what the markers
 * now encode. "No data" is listed as its own entry: a grey marker is an absence
 * of measurement, not a fifth severity, and a reader has to be told which.
 */
function ConditionLegend() {
  const bands: Array<[string, string]> = [
    ["Normal", CONDITION_COLORS.NORMAL],
    ["Moderate", CONDITION_COLORS.MODERATE],
    ["High", CONDITION_COLORS.HIGH],
    ["Critical", CONDITION_COLORS.CRITICAL],
    ["No data", NO_DATA_COLOR],
  ];

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-line-subtle px-5 py-3">
      {bands.map(([label, color]) => (
        <div key={label} className="flex items-center gap-1.5">
          <span
            className="h-2.5 w-2.5 rounded-full"
            style={{ backgroundColor: color }}
            aria-hidden="true"
          />
          <span className="text-[12px] text-content-tertiary">{label}</span>
        </div>
      ))}
    </div>
  );
}
