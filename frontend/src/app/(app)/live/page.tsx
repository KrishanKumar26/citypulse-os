"use client";

import dynamic from "next/dynamic";
import { useMemo, useState } from "react";

import { KpiRow } from "@/components/live/KpiRow";
import { LiveStatusBar } from "@/components/live/LiveStatusBar";
import { Badge, Card, CardHeader, EmptyState, ErrorState, LoadingState, PageHeader } from "@/components/ui";
import { ZONE_TYPE_LABELS } from "@/components/map/ZoneMap";
import type { ConditionLevel, ZoneCondition } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";
import { useLiveSnapshot } from "@/lib/live/useLiveSnapshot";

const ZoneMap = dynamic(() => import("@/components/map/ZoneMap"), {
  ssr: false,
  loading: () => <div className="skeleton h-full w-full" />,
});

/**
 * Live Intelligence (PRD §9).
 *
 * Everything here is pushed, not polled: the page opens a server-sent event
 * stream and re-renders as frames arrive. Nothing on it asks the user to
 * refresh, and nothing is computed client-side that the pipeline already
 * computed — congestion bands, AQI categories and risk scores are read as
 * stored, so what is shown is what the warehouse holds.
 */

const LEVEL_BADGE: Record<ConditionLevel, "normal" | "moderate" | "high" | "critical"> = {
  NORMAL: "normal",
  MODERATE: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
};

export default function LivePage() {
  const { city } = useSelectedCity();
  const { snapshot, status, lastEventAt, error, reconnect } = useLiveSnapshot(city?.slug ?? null);
  const [selectedZoneId, setSelectedZoneId] = useState<string | null>(null);

  const conditions = useMemo(() => {
    const map = new Map<string, ZoneCondition>();
    snapshot?.zones.forEach((zone) => map.set(zone.zoneId, zone));
    return map;
  }, [snapshot]);

  // The map still wants Zone-shaped objects for geometry; conditions carry the
  // colour. Derived rather than fetched separately so both come from the same
  // frame and cannot disagree.
  const mapZones = useMemo(
    () =>
      (snapshot?.zones ?? []).map((z) => ({
        id: z.zoneId,
        cityId: snapshot?.cityId ?? "",
        citySlug: snapshot?.citySlug ?? "",
        code: z.zoneCode,
        name: z.zoneName,
        zoneType: z.zoneType,
        centerLatitude: z.latitude,
        centerLongitude: z.longitude,
        areaSqKm: null,
        population: null,
        roadCapacityVph: null,
        active: true,
        demoData: z.demoData,
      })),
    [snapshot],
  );

  const selected = selectedZoneId ? conditions.get(selectedZoneId) : null;

  if (!city) {
    return <LoadingState label="Loading city" rows={4} />;
  }

  if (error && !snapshot) {
    return (
      <div className="p-5">
        <ErrorState
          title="Live conditions unavailable"
          message={error}
          onRetry={reconnect}
        />
      </div>
    );
  }

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Live Intelligence"
        subtitle={`${city.name} · streaming conditions from curated telemetry`}
      >
        <LiveStatusBar
          snapshot={snapshot}
          status={status}
          lastEventAt={lastEventAt}
          onReconnect={reconnect}
        />
      </PageHeader>

      <KpiRow kpis={snapshot?.kpis ?? null} loading={!snapshot} />

      <div className="grid gap-5 lg:grid-cols-3">
        <Card className="overflow-hidden lg:col-span-2">
          <CardHeader
            title="Zone conditions"
            description="Colour is measured composite risk. Grey means the zone reported nothing recently."
          />
          <div className="h-[460px]">
            {!snapshot ? (
              <div className="skeleton h-full w-full" />
            ) : snapshot.zones.length === 0 ? (
              <EmptyState
                title="No zones defined"
                description="This city has no active zones, so there is nothing to monitor yet."
              />
            ) : (
              <ZoneMap
                city={city}
                zones={mapZones}
                conditions={conditions}
                selectedZoneId={selectedZoneId}
                onSelectZone={(zone) => setSelectedZoneId(zone.id)}
              />
            )}
          </div>
        </Card>

        <ZoneDetail condition={selected} />
      </div>

      <ZoneConditionTable
        zones={snapshot?.zones ?? []}
        loading={!snapshot}
        selectedZoneId={selectedZoneId}
        onSelect={setSelectedZoneId}
      />
    </div>
  );
}

function ZoneDetail({ condition }: { condition: ZoneCondition | null | undefined }) {
  if (!condition) {
    return (
      <Card>
        <CardHeader title="Zone detail" />
        <EmptyState
          title="No zone selected"
          description="Select a zone on the map or in the table to see its latest readings."
        />
      </Card>
    );
  }

  if (!condition.hasData) {
    return (
      <Card>
        <CardHeader title={condition.zoneName} description={ZONE_TYPE_LABELS[condition.zoneType]} />
        <EmptyState
          title="No recent telemetry"
          description="This zone is monitored but has not reported inside the currency window. That usually means its feed has stopped."
        />
      </Card>
    );
  }

  const rows: Array<[string, string]> = [
    ["Congestion", condition.congestionLevel ?? "Not measured"],
    [
      "Occupancy",
      condition.occupancyRatio
        ? `${(Number(condition.occupancyRatio) * 100).toFixed(0)}% of capacity`
        : "Not measured",
    ],
    [
      "Average speed",
      condition.averageSpeedKph ? `${Number(condition.averageSpeedKph).toFixed(1)} km/h` : "Not measured",
    ],
    ["Vehicles", condition.vehicleCount != null ? String(condition.vehicleCount) : "Not measured"],
    [
      "Air quality",
      condition.aqi != null ? `${condition.aqi} (${condition.aqiCategory ?? "—"})` : "Not measured",
    ],
    [
      "Weather",
      condition.temperatureC
        ? `${Number(condition.temperatureC).toFixed(1)}°C, ${condition.weatherCondition?.replace(/_/g, " ").toLowerCase() ?? "—"}`
        : "Not measured",
    ],
    ["Open incidents", String(condition.activeIncidents)],
    ["Scheduled events", String(condition.activeEvents)],
  ];

  return (
    <Card>
      <CardHeader
        title={condition.zoneName}
        description={ZONE_TYPE_LABELS[condition.zoneType]}
        action={
          condition.riskLevel ? (
            <Badge level={LEVEL_BADGE[condition.riskLevel]}>
              {condition.riskLevel}
              {condition.riskScore && ` · ${Number(condition.riskScore).toFixed(0)}`}
            </Badge>
          ) : undefined
        }
      />
      <dl className="divide-y divide-line-subtle">
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-center justify-between gap-4 px-5 py-2.5">
            <dt className="text-[13px] text-content-tertiary">{label}</dt>
            <dd className="text-[13px] tabular text-content-primary">{value}</dd>
          </div>
        ))}
      </dl>
      {/*
        Provenance, shown rather than hidden: the window these numbers came from
        and how many raw events produced them. A figure from a two-event window
        deserves less confidence than one from sixty, and the reader can only
        apply that judgement if the count is visible.
      */}
      <div className="border-t border-line-subtle px-5 py-3 text-[11px] text-content-tertiary">
        Window {condition.windowStart ? new Date(condition.windowStart).toLocaleTimeString() : "—"}
        {" · "}
        {condition.sampleCount} raw {condition.sampleCount === 1 ? "event" : "events"}
      </div>
    </Card>
  );
}

function ZoneConditionTable({
  zones,
  loading,
  selectedZoneId,
  onSelect,
}: {
  zones: ZoneCondition[];
  loading: boolean;
  selectedZoneId: string | null;
  onSelect: (zoneId: string) => void;
}) {
  // Worst first: an operator scanning this list wants the zones that need
  // attention at the top, not alphabetical order.
  const sorted = [...zones].sort((a, b) => {
    const scoreA = a.riskScore ? Number(a.riskScore) : -1;
    const scoreB = b.riskScore ? Number(b.riskScore) : -1;
    return scoreB - scoreA;
  });

  return (
    <Card className="overflow-hidden">
      <CardHeader title="All zones" description="Ordered by composite risk, highest first." />

      {loading ? (
        <LoadingState label="Loading conditions" rows={5} />
      ) : sorted.length === 0 ? (
        <EmptyState title="No zones" description="This city has no active zones." />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-[13px]">
            <thead>
              <tr className="border-b border-line-subtle text-[12px] text-content-tertiary">
                <th scope="col" className="px-5 py-2.5 font-medium">Zone</th>
                <th scope="col" className="px-5 py-2.5 font-medium">Condition</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">Occupancy</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">Speed</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">AQI</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">Incidents</th>
                <th scope="col" className="px-5 py-2.5 text-right font-medium">Risk</th>
              </tr>
            </thead>
            <tbody>
              {sorted.map((zone) => (
                <tr
                  key={zone.zoneId}
                  onClick={() => onSelect(zone.zoneId)}
                  tabIndex={0}
                  onKeyDown={(event) => {
                    if (event.key === "Enter" || event.key === " ") {
                      event.preventDefault();
                      onSelect(zone.zoneId);
                    }
                  }}
                  aria-selected={zone.zoneId === selectedZoneId}
                  className={`cursor-pointer border-b border-line-subtle transition-colors last:border-0 ${
                    zone.zoneId === selectedZoneId ? "bg-accent-subtle" : "hover:bg-surface-hover"
                  }`}
                >
                  <td className="px-5 py-2.5">
                    <div className="font-medium">{zone.zoneName}</div>
                    <div className="text-[11px] text-content-tertiary">{zone.zoneCode}</div>
                  </td>
                  <td className="px-5 py-2.5">
                    {zone.hasData && zone.congestionLevel ? (
                      <Badge level={LEVEL_BADGE[zone.congestionLevel]}>{zone.congestionLevel}</Badge>
                    ) : (
                      <span className="text-[12px] text-content-disabled">No data</span>
                    )}
                  </td>
                  <td className="px-5 py-2.5 text-right tabular">
                    {zone.occupancyRatio
                      ? `${(Number(zone.occupancyRatio) * 100).toFixed(0)}%`
                      : "—"}
                  </td>
                  <td className="px-5 py-2.5 text-right tabular">
                    {zone.averageSpeedKph ? Number(zone.averageSpeedKph).toFixed(0) : "—"}
                  </td>
                  <td className="px-5 py-2.5 text-right tabular">{zone.aqi ?? "—"}</td>
                  <td className="px-5 py-2.5 text-right tabular">
                    {zone.hasData ? zone.activeIncidents : "—"}
                  </td>
                  <td className="px-5 py-2.5 text-right tabular">
                    {zone.riskScore ? Number(zone.riskScore).toFixed(0) : "—"}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}
