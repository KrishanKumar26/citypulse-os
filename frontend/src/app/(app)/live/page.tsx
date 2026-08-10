"use client";

import dynamic from "next/dynamic";
import { useMemo, useState } from "react";

import { KpiRow } from "@/components/live/KpiRow";
import { LiveStatusBar } from "@/components/live/LiveStatusBar";
import {
  Badge,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  Metric,
  PageHeader,
  cn,
} from "@/components/ui";
import { ZONE_TYPE_LABELS } from "@/components/map/ZoneMap";
import type { ConditionLevel, ZoneCondition } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";
import { useLiveSnapshot } from "@/lib/live/useLiveSnapshot";
import { PROVENANCE_LABEL, describeAqi } from "@/lib/provenance";
import { define, describeRisk } from "@/lib/wording";
import { AqiValue } from "@/components/live/AqiValue";

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
        subtitle={`${city.name} · what every part of the city is doing right now`}
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
            description="Colour is the zone's overall risk. Grey means it reported nothing recently."
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

  // Grouped by what a reader is asking. Eight undifferentiated rows make
  // "is traffic bad" and "is the air bad" cost the same search; they are not
  // the same question and rarely asked at the same moment.
  // [label, value, definition?] — the label is the plain phrase, the definition
  // is the exact term and its bounds, shown on hover. Nothing is lost by
  // dropping "occupancy ratio" from the surface as long as it stays one hover
  // away for a reader who needs to cite or search for it.
  type Row = [string, string, string?];
  const groups: Array<[string, Row[]]> = [
    ["Traffic", [
      ["How backed up traffic is", condition.congestionLevel ?? "No reading", define("congestion")],
      [
        "Road capacity in use",
        condition.occupancyRatio
          ? `${(Number(condition.occupancyRatio) * 100).toFixed(0)}% in use`
          : "No reading",
        define("occupancy"),
      ],
      [
        "Average speed",
        condition.averageSpeedKph
          ? `${Number(condition.averageSpeedKph).toFixed(1)} km/h`
          : "No reading",
        define("speed"),
      ],
      ["Vehicles counted", condition.vehicleCount != null ? String(condition.vehicleCount) : "No reading"],
    ]],
    ["Environment", [
      [
        "Air quality",
        // Provenance sits with the number, not in a page-level badge: on this
        // deployment a zone near a monitoring station reads an instrument while
        // the zone beside it reads a model, and a banner cannot say that.
        describeAqi(condition.aqi, condition.aqiCategory, condition.aqiSource),
      ],
      [
        "Weather",
        condition.temperatureC
          ? `${Number(condition.temperatureC).toFixed(1)}°C, ${
              condition.weatherCondition?.replace(/_/g, " ").toLowerCase() ?? "—"
            }${condition.weatherSource ? ` · ${PROVENANCE_LABEL[condition.weatherSource]}` : ""}`
          : "No reading",
      ],
    ]],
    ["Activity", [
      ["Open incidents", String(condition.activeIncidents)],
      ["Scheduled events", String(condition.activeEvents)],
      ["Readings behind this", String(condition.sampleCount), define("samples")],
    ]],
  ];

  return (
    <Card>
      <CardHeader
        title={condition.zoneName}
        description={ZONE_TYPE_LABELS[condition.zoneType]}
        action={
          condition.riskLevel ? (
            <Badge level={LEVEL_BADGE[condition.riskLevel]}>{condition.riskLevel}</Badge>
          ) : undefined
        }
      />

      {/* Composite risk leads: it is the one number that ranks this zone against
          the others, and it was previously only legible inside a badge. */}
      <div className="border-b border-line-subtle px-5 py-4">
        <Metric
          label="Overall risk"
          emphasis="hero"
          value={condition.riskScore === null ? null : Number(condition.riskScore).toFixed(0)}
          unit="/ 100"
          level={condition.riskLevel ? LEVEL_BADGE[condition.riskLevel] : null}
          absenceReason="Not scored"
        />
        <p className="mt-1 text-[12px] text-content-tertiary">
          {describeRisk(condition.riskScore === null ? null : Number(condition.riskScore))}
        </p>
      </div>

      <dl>
        {groups.map(([heading, rows]) => (
          <div key={heading} className="border-b border-line-subtle last:border-0">
            <div className="px-5 pb-1 pt-3 text-[10px] font-medium uppercase tracking-[0.09em] text-content-tertiary">
              {heading}
            </div>
            {rows.map(([label, value, definition]) => (
              <div key={label} className="flex items-center justify-between gap-4 px-5 py-1.5">
                <dt
                  title={definition}
                  className={cn(
                    "text-[13px] text-content-tertiary",
                    // A dotted underline is the long-standing convention for
                    // "there is more here". Without it the tooltip exists and
                    // nobody finds it.
                    definition && "cursor-help decoration-dotted underline-offset-4 hover:underline",
                  )}
                >
                  {label}
                </dt>
                <dd
                  className={cn(
                    "text-[13px] tabular",
                    value === "No reading" ? "text-content-disabled" : "text-content-primary",
                  )}
                >
                  {value}
                </dd>
              </div>
            ))}
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
      <CardHeader title="All zones" description="Worst overall risk first." />

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
                  <td className="px-5 py-2.5 text-right tabular">
                    <AqiValue aqi={zone.aqi} source={zone.aqiSource} />
                  </td>
                  <td className="px-5 py-2.5 text-right tabular">
                    {zone.hasData ? zone.activeIncidents : "—"}
                  </td>
                  {/* The column the table is sorted by, so it carries a mark as
                      well as a number. Fifteen rows of bare digits make the
                      ordering something you verify rather than see. */}
                  <td className="py-2.5 pl-5 pr-5">
                    <RiskCell score={zone.riskScore} level={zone.riskLevel} />
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

/**
 * Risk as a number and a mark on a shared 0–100 track.
 *
 * <p>A zone that has not reported gets neither. Drawing an unreported zone as a
 * zero-length bar would rank a dead feed as the calmest place in the city, which
 * is the failure this table exists to prevent.
 */
function RiskCell({ score, level }: { score: string | null; level: ConditionLevel | null }) {
  if (score === null) {
    return <div className="text-right text-[12px] text-content-disabled">—</div>;
  }

  const value = Number(score);
  const tone: Record<ConditionLevel, string> = {
    NORMAL: "bg-status-normal",
    MODERATE: "bg-status-moderate",
    HIGH: "bg-status-high",
    CRITICAL: "bg-status-critical",
  };

  return (
    <div className="flex items-center justify-end gap-2">
      <div className="h-1.5 w-16 overflow-hidden rounded-full bg-surface-hover">
        <div
          className={cn("h-full rounded-full", level ? tone[level] : "bg-content-tertiary")}
          style={{ width: `${Math.max(0, Math.min(100, value))}%` }}
        />
      </div>
      <span className="w-6 text-right tabular">{value.toFixed(0)}</span>
    </div>
  );
}
