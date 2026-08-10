"use client";

import "leaflet/dist/leaflet.css";
import { CircleMarker, MapContainer, TileLayer, Tooltip, useMap } from "react-leaflet";
import { useEffect, useState } from "react";
import type { City, ConditionLevel, Zone, ZoneCondition, ZoneType } from "@/lib/api/types";

/**
 * Interactive city map (PRD §8, §10).
 *
 * Zones are rendered from their real stored coordinates. Marker radius always
 * encodes road capacity; colour depends on what the platform knows.
 *
 * With `conditions` supplied, colour is the measured composite risk from the
 * latest curated window — never an interpolation or a guess. Without it, colour
 * falls back to zone type, which is a static attribute the platform holds
 * regardless of whether telemetry is flowing. A zone that reports nothing is
 * drawn grey rather than green: "no data" and "healthy" must not look alike.
 */

const ZONE_TYPE_COLORS: Record<ZoneType, string> = {
  COMMERCIAL: "#3d7dff",
  RESIDENTIAL: "#2fb37a",
  INDUSTRIAL: "#e07a3c",
  MIXED: "#9b7cf0",
  TRANSIT_HUB: "#d9a227",
  EDUCATIONAL: "#4a9eda",
  RECREATIONAL: "#3fb9a8",
  AIRPORT: "#e5484d",
};

export const ZONE_TYPE_LABELS: Record<ZoneType, string> = {
  COMMERCIAL: "Commercial",
  RESIDENTIAL: "Residential",
  INDUSTRIAL: "Industrial",
  MIXED: "Mixed use",
  TRANSIT_HUB: "Transit hub",
  EDUCATIONAL: "Educational",
  RECREATIONAL: "Recreational",
  AIRPORT: "Airport",
};

export { ZONE_TYPE_COLORS };

/**
 * Colours for the four condition states (PRD §9).
 *
 * The same scale the badges and KPI tiles use, so a red marker and a red tile
 * mean the same severity. Grey is not a fifth severity — it marks a zone that
 * reported nothing, which must be visually distinct from a calm one rather than
 * blending into the "normal" green.
 */
/**
 * Severity colours, read from the design tokens rather than restated here.
 *
 * These were four hardcoded hexes that matched nothing else in the product: a
 * HIGH zone was one orange on the map and a different orange in the badge
 * beside it, for the same word about the same zone. Three severity palettes
 * existed — tokens, chart, map — and nothing kept them in step.
 *
 * Leaflet takes plain colour strings rather than CSS values, so the tokens are
 * resolved once in the browser. The literals below are only the pre-hydration
 * fallback and are kept identical to the tokens by a test.
 */
const FALLBACK_CONDITION_COLORS: Record<ConditionLevel, string> = {
  NORMAL: "#10b981",
  MODERATE: "#facc15",
  HIGH: "#f97316",
  CRITICAL: "#e11d48",
};

const TOKEN_BY_LEVEL: Record<ConditionLevel, string> = {
  NORMAL: "--color-status-normal",
  MODERATE: "--color-status-moderate",
  HIGH: "--color-status-high",
  CRITICAL: "--color-status-critical",
};

function resolveConditionColors(): Record<ConditionLevel, string> {
  if (typeof window === "undefined") return FALLBACK_CONDITION_COLORS;
  const root = getComputedStyle(document.documentElement);
  const resolved = {} as Record<ConditionLevel, string>;
  (Object.keys(TOKEN_BY_LEVEL) as ConditionLevel[]).forEach((level) => {
    resolved[level] =
      root.getPropertyValue(TOKEN_BY_LEVEL[level]).trim() || FALLBACK_CONDITION_COLORS[level];
  });
  return resolved;
}

/**
 * The marker colours for the theme currently applied.
 *
 * A hook rather than a module constant. It was resolved once when the module
 * loaded, which was correct while there was one theme and silently wrong the
 * moment there were two: switching to light left every marker, and the legend
 * beside them, painted in the dark theme's brighter status colours. Nothing
 * would have looked broken — they are the same four hues — so the map would
 * simply have been reading the wrong palette for as long as the tab stayed
 * open.
 *
 * `data-theme` is watched rather than the media query, because it is what the
 * stylesheet keys on and it moves for both causes: an explicit choice and a
 * system change while none is held.
 */
export function useConditionColors(): Record<ConditionLevel, string> {
  const [colors, setColors] = useState<Record<ConditionLevel, string>>(
    FALLBACK_CONDITION_COLORS,
  );

  useEffect(() => {
    const read = () => setColors(resolveConditionColors());
    read();

    const observer = new MutationObserver(read);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme"],
    });
    return () => observer.disconnect();
  }, []);

  return colors;
}

/** The pre-hydration values, for anything that must have a colour synchronously. */
const CONDITION_COLORS: Record<ConditionLevel, string> = resolveConditionColors();

/**
 * What the marker colour means.
 *
 * The map encoded exactly one thing — composite risk — so a reader asking "where
 * is the air worst?" had to leave it and read the table. Each layer keeps the
 * same geometry and re-encodes colour, which is the cheapest possible way to
 * answer a different question about the same places.
 *
 * Radius stays road capacity in every layer. Two channels changing at once would
 * make it impossible to tell which one moved.
 */
export type MapLayer = "risk" | "traffic" | "incidents" | "air";

export const MAP_LAYERS: { id: MapLayer; label: string; legend: string }[] = [
  { id: "risk", label: "Risk", legend: "Measured composite risk" },
  { id: "traffic", label: "Traffic", legend: "Share of road capacity in use" },
  { id: "incidents", label: "Incidents", legend: "Active incidents in the zone" },
  { id: "air", label: "Air quality", legend: "Air quality index" },
];

/**
 * Colour for a zone under the chosen layer, or null when it has no reading for
 * that layer specifically.
 *
 * Null is not the same as a zone with no telemetry at all: a zone reporting
 * traffic but no air quality is grey on the air layer and coloured on the
 * others, which is exactly what the reader should see.
 */
export function colorForLayer(
  layer: MapLayer,
  condition: ZoneCondition,
  /**
   * The palette to draw from. Defaults to the pre-hydration values so existing
   * callers and tests are unchanged; the map and the legend pass the themed set
   * from `useConditionColors`, which is what makes the markers follow a switch.
   */
  palette: Record<ConditionLevel, string> = CONDITION_COLORS,
): string | null {
  const band = (value: number, thresholds: [number, number, number]): string =>
    value >= thresholds[2] ? palette.CRITICAL
      : value >= thresholds[1] ? palette.HIGH
      : value >= thresholds[0] ? palette.MODERATE
      : palette.NORMAL;

  switch (layer) {
    case "risk":
      return condition.riskLevel ? palette[condition.riskLevel] : null;
    case "traffic":
      // The pipeline's own classification, not a re-banding of the ratio. The
      // first attempt here restated the thresholds from common/transforms.py
      // and got them wrong — 0.6/0.85 against the real 0.55/0.80 — which would
      // have coloured a band of zones one level calmer than the table beside
      // them said they were. congestion_level is computed once, upstream, and
      // travels with the reading.
      return condition.congestionLevel
        ? palette[condition.congestionLevel as ConditionLevel]
        : null;
    case "incidents":
      return condition.activeIncidents === 0
        ? palette.NORMAL
        : band(condition.activeIncidents, [1, 2, 4]);
    case "air":
      // CPCB AQI bands: satisfactory / moderate / poor / very poor upward.
      return condition.aqi === null ? null : band(condition.aqi, [100, 200, 300]);
  }
}

const NO_DATA_COLOR = "#6b7a94";

export { CONDITION_COLORS, NO_DATA_COLOR };

interface ZoneMapProps {
  city: City;
  zones: Zone[];
  /** Which measurement colour encodes. Defaults to risk, the map's original. */
  layer?: MapLayer;
  selectedZoneId: string | null;
  onSelectZone: (zone: Zone) => void;
  /**
   * Live conditions keyed by zone id.
   *
   * When supplied, markers are coloured by measured risk instead of by zone
   * type — the map stops describing what a place *is* and starts showing how it
   * is *doing*, which is the whole point of the Command Center. Left undefined
   * the map falls back to zone type, so it still renders before telemetry
   * arrives rather than going blank.
   */
  conditions?: Map<string, ZoneCondition>;
}

/**
 * Whether the light theme is applied, watched rather than read once.
 *
 * Same reason as the palette: the basemap and the marker colours both have to
 * move when the reader switches, and neither is a CSS property that could just
 * follow a variable — Leaflet takes a URL and a colour string.
 */
function useIsLightTheme(): boolean {
  const [light, setLight] = useState(false);

  useEffect(() => {
    const read = () =>
      setLight(document.documentElement.getAttribute("data-theme") === "light");
    read();
    const observer = new MutationObserver(read);
    observer.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-theme"],
    });
    return () => observer.disconnect();
  }, []);

  return light;
}


export default function ZoneMap({
  city,
  zones,
  layer = "risk",
  selectedZoneId,
  onSelectZone,
  conditions,
}: ZoneMapProps) {
  const center: [number, number] = [Number(city.centerLatitude), Number(city.centerLongitude)];
  const palette = useConditionColors();
  const basemap = useIsLightTheme() ? "light_all" : "dark_all";

  return (
    <MapContainer
      center={center}
      zoom={city.defaultZoom}
      scrollWheelZoom
      className="h-full w-full bg-surface-base"
      // Leaflet's default attribution control is repositioned by CSS below; the
      // attribution itself is required by the OpenStreetMap tile licence.
      attributionControl
    >
      <RecenterOnCityChange center={center} zoom={city.defaultZoom} />

      <TileLayer
        // The basemap follows the theme. Left on dark tiles, the light theme
        // put a near-black map inside a white page — the one component on the
        // screen that had not switched, and the most visually dominant.
        //
        // `key` forces Leaflet to replace the layer rather than mutate it: the
        // url prop is read when the layer is created, so without this the tiles
        // stay whichever set was loaded first.
        //
        // If tiles fail to load — offline, or blocked — the base layer is
        // simply empty and the zone markers still render, so the view degrades
        // rather than breaking.
        key={basemap}
        url={`https://{s}.basemaps.cartocdn.com/${basemap}/{z}/{x}/{y}{r}.png`}
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>'
        maxZoom={19}
      />

      {zones.map((zone) => {
        const selected = zone.id === selectedZoneId;
        const condition = conditions?.get(zone.id);

        // Silent zones are drawn grey rather than dropped. A zone vanishing from
        // the map as its feed dies hides the outage; a grey marker shows it.
        const color = condition
          ? condition.hasData
            ? (colorForLayer(layer, condition, palette) ?? NO_DATA_COLOR)
            : NO_DATA_COLOR
          : ZONE_TYPE_COLORS[zone.zoneType];

        return (
          <CircleMarker
            key={zone.id}
            center={[Number(zone.centerLatitude), Number(zone.centerLongitude)]}
            radius={radiusForCapacity(zone.roadCapacityVph)}
            pathOptions={{
              color,
              weight: selected ? 3 : 1.5,
              fillColor: color,
              fillOpacity: selected ? 0.55 : 0.28,
            }}
            eventHandlers={{ click: () => onSelectZone(zone) }}
          >
            <Tooltip direction="top" offset={[0, -6]}>
              <div className="text-[12px]">
                <div className="font-medium">{zone.name}</div>
                <div className="opacity-70">
                  {zone.code} · {ZONE_TYPE_LABELS[zone.zoneType]}
                </div>
                {condition && (
                  <div className="mt-1 opacity-90">
                    {condition.hasData ? (
                      <>
                        {condition.riskLevel} · risk{" "}
                        {condition.riskScore ? Number(condition.riskScore).toFixed(0) : "—"}
                        {condition.averageSpeedKph && (
                          <> · {Number(condition.averageSpeedKph).toFixed(0)} km/h</>
                        )}
                      </>
                    ) : (
                      "No recent telemetry"
                    )}
                  </div>
                )}
              </div>
            </Tooltip>
          </CircleMarker>
        );
      })}
    </MapContainer>
  );
}

/**
 * Leaflet keeps its initial centre when props change, so switching cities would
 * otherwise leave the viewport over the previous one.
 */
function RecenterOnCityChange({ center, zoom }: { center: [number, number]; zoom: number }) {
  const map = useMap();

  useEffect(() => {
    map.setView(center, zoom);
    // center is a fresh array each render; depending on its values avoids an
    // infinite re-centre loop.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [map, center[0], center[1], zoom]);

  return null;
}

/**
 * Marker size from road capacity, on a square-root scale so area — which is
 * what the eye actually compares — stays proportional to the value.
 */
function radiusForCapacity(capacityVph: number | null): number {
  if (!capacityVph) return 7;
  const scaled = Math.sqrt(capacityVph) / 9;
  return Math.min(20, Math.max(7, scaled));
}
