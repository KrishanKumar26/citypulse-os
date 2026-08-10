"use client";

import { cn } from "@/components/ui";
import type { CityKpis, ConditionLevel } from "@/lib/api/types";

/**
 * Composite risk as a ring, with the measurements that produced it beneath.
 *
 * A single number out of 100 is hard to place without knowing the scale — 55
 * means nothing until you know where the bands fall. The arc puts the value on
 * its scale, and the banding beneath names the four ranges, so the reader learns
 * the scale from the control rather than from documentation.
 *
 * The contributions are the honest part. Composite risk is derived from
 * congestion, environment and incidents, and a reader who sees 55 immediately
 * asks *of what*. Showing the three inputs answers it, and shows when one input
 * is carrying the whole score — a city at 55 because of one bad incident needs a
 * different response from one at 55 because every road is full.
 *
 * The contributions shown are the measurements themselves, normalised to the
 * same 0-100 scale. They are deliberately not presented as a decomposition of
 * the score: the backend computes the composite with its own weights, and
 * splitting 55 into "31 traffic, 14 environment, 10 incidents" here would be
 * inventing an attribution the platform never calculated.
 */

const BANDS: { upTo: number; label: string; level: ConditionLevel }[] = [
  { upTo: 30, label: "Healthy", level: "NORMAL" },
  { upTo: 50, label: "Moderate", level: "MODERATE" },
  { upTo: 70, label: "High", level: "HIGH" },
  { upTo: 100, label: "Critical", level: "CRITICAL" },
];

const LEVEL_VAR: Record<ConditionLevel, string> = {
  NORMAL: "var(--color-status-normal)",
  MODERATE: "var(--color-status-moderate)",
  HIGH: "var(--color-status-high)",
  CRITICAL: "var(--color-status-critical)",
};

const LEVEL_TEXT: Record<ConditionLevel, string> = {
  NORMAL: "text-status-normal",
  MODERATE: "text-status-moderate",
  HIGH: "text-status-high",
  CRITICAL: "text-status-critical",
};

function bandFor(score: number) {
  return BANDS.find((band) => score <= band.upTo) ?? BANDS[BANDS.length - 1];
}

/** Geometry: a 240° arc, opening downward so the label sits in the gap. */
const RADIUS = 52;
const SWEEP = 240;
const START = 150; // degrees, clockwise from 3 o'clock
const CIRCUMFERENCE = 2 * Math.PI * RADIUS;
const ARC_LENGTH = (SWEEP / 360) * CIRCUMFERENCE;

export function CityHealthRing({ kpis }: { kpis: CityKpis | null }) {
  const score = kpis?.averageRiskScore != null ? Number(kpis.averageRiskScore) : null;
  const band = score !== null ? bandFor(score) : null;

  return (
    <div className="flex flex-col items-center px-5 py-5">
      <div className="relative" style={{ width: 148, height: 116 }}>
        <svg width="148" height="148" viewBox="0 0 148 148" className="absolute -top-1 left-0">
          <g transform="translate(74,74)">
            {/* Track, and the four bands drawn on it so the scale is visible
                rather than implied. */}
            <circle
              r={RADIUS}
              fill="none"
              stroke="var(--color-surface-hover)"
              strokeWidth={9}
              strokeLinecap="round"
              strokeDasharray={`${ARC_LENGTH} ${CIRCUMFERENCE}`}
              transform={`rotate(${START})`}
            />
            {BANDS.map((b, i) => {
              const from = i === 0 ? 0 : BANDS[i - 1].upTo;
              const len = ((b.upTo - from) / 100) * ARC_LENGTH;
              const offset = (from / 100) * ARC_LENGTH;
              return (
                <circle
                  key={b.label}
                  r={RADIUS}
                  fill="none"
                  stroke={LEVEL_VAR[b.level]}
                  strokeWidth={2}
                  opacity={0.3}
                  strokeDasharray={`${Math.max(len - 2, 1)} ${CIRCUMFERENCE}`}
                  strokeDashoffset={-offset}
                  transform={`rotate(${START})`}
                />
              );
            })}

            {score !== null && band && (
              <circle
                r={RADIUS}
                fill="none"
                stroke={LEVEL_VAR[band.level]}
                strokeWidth={9}
                strokeLinecap="round"
                strokeDasharray={`${(score / 100) * ARC_LENGTH} ${CIRCUMFERENCE}`}
                transform={`rotate(${START})`}
                style={{ transition: "stroke-dasharray 500ms ease-out, stroke 300ms" }}
              />
            )}
          </g>
        </svg>

        <div className="absolute inset-x-0 top-[34px] flex flex-col items-center">
          {score === null ? (
            <span className="text-[13px] text-content-disabled">No reading</span>
          ) : (
            <>
              <div className="flex items-baseline gap-1">
                <span className={cn("tabular text-[40px] font-semibold leading-none tracking-tight", band && LEVEL_TEXT[band.level])}>
                  {score.toFixed(0)}
                </span>
                <span className="text-[13px] text-content-tertiary">/ 100</span>
              </div>
              <span className={cn("mt-1.5 text-[11px] font-medium uppercase tracking-[0.1em]", band && LEVEL_TEXT[band.level])}>
                {band?.label}
              </span>
            </>
          )}
        </div>
      </div>

      <p className="mt-1 text-[11px] text-content-tertiary">
        {kpis ? `${kpis.zonesReporting} of ${kpis.zonesMonitored} zones reporting` : " "}
      </p>

      <Contributions kpis={kpis} />
    </div>
  );
}

/**
 * The measurements behind the score, on a common 0-100 scale.
 *
 * Labelled "measured inputs", not "contributions to the score" — see the note
 * at the top of the file. Each bar is the input itself, so a reader can see
 * which signal is elevated without the UI claiming a weighting it did not do.
 */
function Contributions({ kpis }: { kpis: CityKpis | null }) {
  const rows: { label: string; value: number | null; suffix: string }[] = [
    {
      label: "Traffic load",
      value: kpis?.averageCongestion != null ? Number(kpis.averageCongestion) * 100 : null,
      suffix: "% of capacity",
    },
    {
      label: "Air quality",
      // AQI runs 0-500; the risk-relevant range saturates well below that, so it
      // is shown against 300 rather than compressed against the full scale.
      value: kpis?.averageAqi != null ? (kpis.averageAqi / 300) * 100 : null,
      suffix: kpis?.averageAqi != null ? `AQI ${kpis.averageAqi}` : "",
    },
    {
      label: "Incidents",
      value:
        kpis?.activeIncidents != null && kpis.zonesMonitored > 0
          ? Math.min((kpis.activeIncidents / kpis.zonesMonitored) * 100, 100)
          : null,
      suffix: kpis?.activeIncidents != null ? `${kpis.activeIncidents} active` : "",
    },
  ];

  return (
    <div className="mt-4 w-full space-y-2.5 border-t border-line-subtle pt-4">
      <p className="text-[10px] font-medium uppercase tracking-[0.1em] text-content-tertiary">
        What went into this
      </p>
      {rows.map((row) => (
        <div key={row.label} className="flex items-center gap-3">
          <span className="w-[74px] shrink-0 text-[11px] text-content-secondary">{row.label}</span>
          <div className="h-1 flex-1 overflow-hidden rounded-full bg-surface-hover">
            {row.value !== null && (
              <div
                className="h-full rounded-full bg-accent-mark"
                style={{
                  width: `${Math.min(Math.max(row.value, 0), 100)}%`,
                  transition: "width 500ms ease-out",
                }}
              />
            )}
          </div>
          <span className="w-[86px] shrink-0 text-right text-[10px] tabular text-content-tertiary">
            {row.value === null ? "no reading" : row.suffix}
          </span>
        </div>
      ))}
    </div>
  );
}
