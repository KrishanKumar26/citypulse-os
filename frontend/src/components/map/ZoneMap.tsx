"use client";

import "leaflet/dist/leaflet.css";
import { CircleMarker, MapContainer, TileLayer, Tooltip, useMap } from "react-leaflet";
import { useEffect } from "react";
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
const CONDITION_COLORS: Record<ConditionLevel, string> = {
  NORMAL: "#3fb950",
  MODERATE: "#d29922",
  HIGH: "#db6d28",
  CRITICAL: "#f85149",
};

const NO_DATA_COLOR = "#6e7681";

export { CONDITION_COLORS, NO_DATA_COLOR };

interface ZoneMapProps {
  city: City;
  zones: Zone[];
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

export default function ZoneMap({
  city,
  zones,
  selectedZoneId,
  onSelectZone,
  conditions,
}: ZoneMapProps) {
  const center: [number, number] = [Number(city.centerLatitude), Number(city.centerLongitude)];

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
        // CARTO dark tiles suit the dark design system. If tiles fail to load —
        // offline, or blocked — the base layer is simply empty and the zone
        // markers still render, so the view degrades rather than breaking.
        url="https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png"
        attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors &copy; <a href="https://carto.com/attributions">CARTO</a>'
        maxZoom={19}
      />

      {zones.map((zone) => {
        const selected = zone.id === selectedZoneId;
        const condition = conditions?.get(zone.id);

        // Silent zones are drawn grey rather than dropped. A zone vanishing from
        // the map as its feed dies hides the outage; a grey marker shows it.
        const color = condition
          ? condition.hasData && condition.riskLevel
            ? CONDITION_COLORS[condition.riskLevel]
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
