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
  Skeleton,
} from "@/components/ui";
import {
  CONDITION_COLORS,
  NO_DATA_COLOR,
  ZONE_TYPE_COLORS,
  ZONE_TYPE_LABELS,
} from "@/components/map/ZoneMap";
import { geoApi } from "@/lib/api/endpoints";
import type { Zone, ZoneCondition } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";
import { useLiveSnapshot } from "@/lib/live/useLiveSnapshot";
import { KpiRow } from "@/components/live/KpiRow";
import { RiskDistribution, ZoneRiskChart } from "@/components/charts/ZoneRiskChart";
import { ZoneIntelligence } from "@/components/live/ZoneIntelligence";
import { LiveStatusBar } from "@/components/live/LiveStatusBar";
import { formatArea, formatNumber } from "@/lib/format";

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

  const zonesQuery = useQuery({
    queryKey: ["zones", city?.id],
    queryFn: () => geoApi.listZones(city!.id, true),
    enabled: Boolean(city),
  });

  const { snapshot, status, lastEventAt, reconnect } = useLiveSnapshot(city?.slug ?? null);

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
      <header className="mb-5 flex flex-wrap items-start justify-between gap-3">
        <div>
          {/*
            Labelled once, in the top bar, which is on every page and now shows
            at every width. Three identical "DEMO DATA" badges shared this
            screen — top bar, here, and the status strip. Repetition does not
            make the disclosure stronger; it turns it into decoration people
            stop reading, which is the opposite of what PRD §42 is for.
          */}
          <h1 className="text-lg font-semibold tracking-tight">{city.name}</h1>
          <p className="mt-1 text-[13px] text-content-tertiary">
            {city.country} · {city.timezone} · {city.zoneCount} monitored{" "}
            {city.zoneCount === 1 ? "zone" : "zones"}
          </p>
        </div>
      </header>

      <div className="mb-5">
        <LiveStatusBar
          snapshot={snapshot}
          status={status}
          lastEventAt={lastEventAt}
          onReconnect={reconnect}
        />
      </div>

      <KpiRow kpis={snapshot?.kpis ?? null} loading={!snapshot} />

      <div className="mt-5">
        <CoverageMetrics city={city} zones={zones} loading={zonesQuery.isLoading} />
      </div>

      <div className="mt-5 grid gap-5 lg:grid-cols-3">
        <Card className="overflow-hidden lg:col-span-2">
          <CardHeader
            title="City map"
            description="Marker size is road capacity; colour is measured composite risk. Grey means the zone reported nothing recently."
          />
          <div className="h-[460px]">
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

function ZoneTable({
  zones,
  loading,
  selectedZoneId,
  onSelectZone,
}: {
  zones: Zone[];
  loading: boolean;
  selectedZoneId: string | null;
  onSelectZone: (zone: Zone) => void;
}) {
  return (
    <Card className="overflow-hidden">
      <CardHeader title="Zones" description="All active monitored zones in the selected city." />

      {loading ? (
        <LoadingState label="Loading zones" rows={5} />
      ) : zones.length === 0 ? (
        <EmptyState title="No zones" description="This city has no active zones." />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-[13px]">
            <thead>
              <tr className="border-b border-line-subtle text-[12px] text-content-tertiary">
                <th scope="col" className="px-5 py-2.5 font-medium">Zone</th>
                <th scope="col" className="px-5 py-2.5 font-medium">Code</th>
                <th scope="col" className="px-5 py-2.5 font-medium">Type</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">Capacity</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">Population</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">Area</th>
              </tr>
            </thead>
            <tbody>
              {zones.map((zone) => (
                <tr
                  key={zone.id}
                  onClick={() => onSelectZone(zone)}
                  // Rows are focusable and activate on Enter, so the table is
                  // usable without a pointer.
                  tabIndex={0}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      onSelectZone(zone);
                    }
                  }}
                  aria-selected={zone.id === selectedZoneId}
                  className={`cursor-pointer border-b border-line-subtle transition-colors last:border-0 ${
                    zone.id === selectedZoneId ? "bg-accent-subtle" : "hover:bg-surface-hover"
                  }`}
                >
                  <td className="px-5 py-2.5">
                    <div className="flex items-center gap-2">
                      <span
                        className="h-2 w-2 shrink-0 rounded-full"
                        style={{ backgroundColor: ZONE_TYPE_COLORS[zone.zoneType] }}
                        aria-hidden="true"
                      />
                      {zone.name}
                    </div>
                  </td>
                  <td className="px-5 py-2.5 font-mono text-[12px] text-content-tertiary">{zone.code}</td>
                  <td className="px-5 py-2.5 text-content-secondary">{ZONE_TYPE_LABELS[zone.zoneType]}</td>
                  <td className="px-5 py-2.5 text-right tabular">
                    {zone.roadCapacityVph ? formatNumber(zone.roadCapacityVph) : "—"}
                  </td>
                  <td className="px-5 py-2.5 text-right tabular">
                    {zone.population ? formatNumber(zone.population) : "—"}
                  </td>
                  <td className="px-5 py-2.5 text-right tabular">{formatArea(zone.areaSqKm)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
