"use client";

import { useMemo, useState } from "react";
import { Badge, Card, CardHeader, EmptyState, Input, LoadingState, cn } from "@/components/ui";
import { TrendBadge } from "@/components/charts/Sparkline";
import type { AirProvenance, ConditionLevel, Zone, ZoneCondition } from "@/lib/api/types";
import { AqiValue, TrafficValue } from "@/components/live/AqiValue";

/**
 * Every zone's current condition, sortable and filterable.
 *
 * What this replaces listed zone code, type, road capacity, population and area
 * — reference data that has not changed since the city was seeded — while taking
 * the full width beneath a live dashboard. An operator scanning it learned
 * nothing about the evening.
 *
 * The columns are now the measurements: condition, occupancy, speed, air
 * quality, incidents and risk. Geography moved to the zone panel, where it is
 * context for a zone someone has already chosen rather than the answer to
 * "where should I look".
 *
 * The trend column compares each zone's risk against its own window about an
 * hour earlier, which the snapshot now carries. It was left out until the API
 * could supply that: derived from consecutive snapshots it would have printed
 * "steady" on every row, since the hosted pipeline writes hourly and successive
 * snapshots hold identical numbers. A column that always says the same thing is
 * worse than an absent one, because it looks like a measurement.
 *
 * A zone with no window an hour back shows "no trend yet" rather than a flat
 * arrow. Unknown and unchanged are different, and the arrow is the place that
 * conflation would be least visible.
 */

const LEVELS: ConditionLevel[] = ["CRITICAL", "HIGH", "MODERATE", "NORMAL"];

const LEVEL_WORD: Record<ConditionLevel, string> = {
  NORMAL: "Normal",
  MODERATE: "Elevated",
  HIGH: "High",
  CRITICAL: "Critical",
};

const LEVEL_TO_STATUS = {
  NORMAL: "normal",
  MODERATE: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
} as const;

type SortKey = "name" | "risk" | "occupancy" | "speed" | "aqi" | "incidents";

interface Row {
  zone: Zone;
  condition: ZoneCondition | undefined;
  risk: number | null;
  occupancy: number | null;
  /** Current speed over free flow. Present instead of occupancy, never with it. */
  speedRatio: number | null;
  trafficSource: AirProvenance | null;
  speed: number | null;
  aqi: number | null;
  aqiSource: AirProvenance | null;
  incidents: number | null;
  level: ConditionLevel | null;
  /** Percentage change in risk against the zone's own earlier window. */
  trend: number | null;
}

export function ZoneTable({
  zones,
  conditions,
  loading,
  selectedZoneId,
  onSelectZone,
}: {
  zones: Zone[];
  conditions: Map<string, ZoneCondition>;
  loading: boolean;
  selectedZoneId: string | null;
  onSelectZone: (zone: Zone) => void;
}) {
  const [query, setQuery] = useState("");
  const [levelFilter, setLevelFilter] = useState<ConditionLevel | "ALL">("ALL");
  const [sort, setSort] = useState<{ key: SortKey; desc: boolean }>({ key: "risk", desc: true });

  const rows = useMemo<Row[]>(
    () =>
      zones.map((zone) => {
        const c = conditions.get(zone.id);
        const has = Boolean(c?.hasData);
        return {
          zone,
          condition: c,
          risk: has && c?.riskScore != null ? Number(c.riskScore) : null,
          occupancy: has && c?.occupancyRatio != null ? Number(c.occupancyRatio) : null,
          speedRatio: has && c?.speedRatio != null ? Number(c.speedRatio) : null,
          trafficSource:
            has && (c?.occupancyRatio != null || c?.speedRatio != null)
              ? (c?.trafficSource ?? null)
              : null,
          speed: has && c?.averageSpeedKph != null ? Number(c.averageSpeedKph) : null,
          aqi: has && c?.aqi != null ? c.aqi : null,
          aqiSource: has && c?.aqi != null ? (c.aqiSource ?? null) : null,
          incidents: has && c ? c.activeIncidents : null,
          level: has ? (c?.riskLevel ?? null) : null,
          trend:
            has && c?.riskScore != null && c.previousRiskScore != null
              && Number(c.previousRiskScore) !== 0
              ? ((Number(c.riskScore) - Number(c.previousRiskScore))
                  / Math.abs(Number(c.previousRiskScore))) * 100
              : null,
        };
      }),
    [zones, conditions],
  );

  const visible = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const filtered = rows.filter((r) => {
      if (levelFilter !== "ALL" && r.level !== levelFilter) return false;
      if (!needle) return true;
      return (
        r.zone.name.toLowerCase().includes(needle) || r.zone.code.toLowerCase().includes(needle)
      );
    });

    const value = (r: Row): number | string | null => {
      switch (sort.key) {
        case "name": return r.zone.name.toLowerCase();
        case "risk": return r.risk;
        // Inverted for the speed form: 0.5 of free flow is a worse road than
        // 0.9, while 0.5 of capacity is a better one than 0.9. Sorting the raw
        // numbers together would interleave the two feeds backwards.
        case "occupancy":
          return r.occupancy !== null
            ? r.occupancy
            : r.speedRatio !== null ? 1 - r.speedRatio : null;
        case "speed": return r.speed;
        case "aqi": return r.aqi;
        case "incidents": return r.incidents;
      }
    };

    return [...filtered].sort((a, b) => {
      const av = value(a);
      const bv = value(b);
      // Zones with no reading sort last whichever way the column is pointed.
      // Treating absence as zero would rank a silent feed as the calmest zone
      // when sorting by risk, and as the fastest when sorting by speed.
      if (av === null && bv === null) return 0;
      if (av === null) return 1;
      if (bv === null) return -1;
      const cmp = typeof av === "string" ? av.localeCompare(bv as string) : (av as number) - (bv as number);
      return sort.desc ? -cmp : cmp;
    });
  }, [rows, query, levelFilter, sort]);

  const silent = rows.filter((r) => r.level === null).length;

  return (
    <Card className="overflow-hidden">
      <CardHeader
        title="Zones"
        description={
          silent > 0
            ? `${rows.length} monitored · ${silent} with no recent reading`
            : `${rows.length} monitored, all reporting`
        }
      />

      <div className="flex flex-wrap items-center gap-2 border-b border-line-subtle px-5 py-3">
        <Input
          label="Search zones"
          hideLabel
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search zone or code"
          className="h-8 w-full max-w-[220px] text-[12px]"
        />
        <div className="flex flex-wrap items-center gap-1">
          <FilterChip active={levelFilter === "ALL"} onClick={() => setLevelFilter("ALL")}>
            All
          </FilterChip>
          {LEVELS.map((level) => {
            const count = rows.filter((r) => r.level === level).length;
            return (
              <FilterChip
                key={level}
                active={levelFilter === level}
                onClick={() => setLevelFilter(levelFilter === level ? "ALL" : level)}
                // The count is on the chip so a filter that would empty the
                // table announces it before it is clicked.
                disabled={count === 0}
              >
                <span
                  aria-hidden="true"
                  className="mr-1.5 inline-block h-1.5 w-1.5 rounded-full align-middle"
                  style={{ background: `var(--color-status-${LEVEL_TO_STATUS[level]})` }}
                />
                {LEVEL_WORD[level]} {count}
              </FilterChip>
            );
          })}
        </div>
      </div>

      {loading ? (
        <LoadingState label="Loading zones" rows={5} />
      ) : visible.length === 0 ? (
        <EmptyState
          title={rows.length === 0 ? "No zones" : "Nothing matches"}
          description={
            rows.length === 0
              ? "This city has no active zones."
              : "No zone matches the current search and filter."
          }
        />
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-[12px]">
            <thead>
              <tr className="border-b border-line-subtle text-[11px] text-content-tertiary">
                <SortableHeader label="Zone" col="name" sort={sort} setSort={setSort} align="left" />
                <th scope="col" className="px-4 py-2.5 font-medium">Condition</th>
                <SortableHeader label="Road load" col="occupancy" sort={sort} setSort={setSort} />
                <SortableHeader label="Speed" col="speed" sort={sort} setSort={setSort} />
                <SortableHeader label="AQI" col="aqi" sort={sort} setSort={setSort} />
                <SortableHeader label="Incidents" col="incidents" sort={sort} setSort={setSort} />
                <SortableHeader label="Risk" col="risk" sort={sort} setSort={setSort} />
                <th scope="col" className="px-4 py-2.5 text-right font-medium">Trend</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((r) => {
                const selected = r.zone.id === selectedZoneId;
                return (
                  <tr
                    key={r.zone.id}
                    onClick={() => onSelectZone(r.zone)}
                    aria-selected={selected}
                    className={cn(
                      "cursor-pointer border-b border-line-subtle transition-colors last:border-0",
                      selected ? "bg-accent-subtle" : "hover:bg-surface-hover",
                    )}
                  >
                    <td className="px-4 py-2.5">
                      <span className="block text-content-primary">{r.zone.name}</span>
                      <span className="text-[10px] text-content-tertiary">{r.zone.code}</span>
                    </td>
                    <td className="px-4 py-2.5">
                      {r.level ? (
                        <Badge level={LEVEL_TO_STATUS[r.level]}>{LEVEL_WORD[r.level]}</Badge>
                      ) : (
                        // Not a dash. A zone whose feed has stopped is a
                        // different state from a zone that is fine, and the
                        // table is where that difference is easiest to miss.
                        <span className="text-[11px] text-content-disabled">No reading</span>
                      )}
                    </td>
                    <td className="px-4 py-2.5 text-right tabular">
                      <TrafficValue
                        occupancy={r.occupancy}
                        speedRatio={r.speedRatio}
                        source={r.trafficSource}
                      />
                    </td>
                    <Cell value={r.speed} suffix=" km/h" decimals={1} />
                    <td className="px-4 py-2.5 text-right tabular">
                      <AqiValue aqi={r.aqi} source={r.aqiSource} />
                    </td>
                    <Cell value={r.incidents} decimals={0} />
                    <Cell value={r.risk} decimals={0} strong />
                    <td className="px-4 py-2.5 text-right">
                      <TrendBadge change={r.trend} />
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}
    </Card>
  );
}

function Cell({
  value,
  suffix = "",
  decimals,
  strong,
}: {
  value: number | null;
  suffix?: string;
  decimals: number;
  strong?: boolean;
}) {
  return (
    <td className="px-4 py-2.5 text-right tabular">
      {value === null ? (
        <span className="text-content-disabled">—</span>
      ) : (
        <span className={strong ? "font-medium text-content-primary" : "text-content-secondary"}>
          {value.toFixed(decimals)}
          {suffix}
        </span>
      )}
    </td>
  );
}

function SortableHeader({
  label,
  col,
  sort,
  setSort,
  align = "right",
}: {
  label: string;
  col: SortKey;
  sort: { key: SortKey; desc: boolean };
  setSort: (s: { key: SortKey; desc: boolean }) => void;
  align?: "left" | "right";
}) {
  const active = sort.key === col;
  return (
    <th
      scope="col"
      aria-sort={active ? (sort.desc ? "descending" : "ascending") : "none"}
      className={cn("px-4 py-2.5 font-medium", align === "right" ? "text-right" : "text-left")}
    >
      <button
        type="button"
        onClick={() => setSort({ key: col, desc: active ? !sort.desc : true })}
        className={cn(
          "inline-flex items-center gap-1 transition-colors hover:text-content-primary",
          active && "text-content-primary",
        )}
      >
        {label}
        <span aria-hidden="true" className={cn("text-[10px]", !active && "opacity-0")}>
          {sort.desc ? "▼" : "▲"}
        </span>
      </button>
    </th>
  );
}

function FilterChip({
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
