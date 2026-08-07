"use client";

import { useMemo, useState } from "react";

/**
 * One measurement over time.
 *
 * The workhorse behind the analytics page. A single series, a shared vertical
 * scale, a crosshair that reads the value under the pointer — the plain form,
 * because every question these charts answer is "what has this been doing", and
 * anything added to that is decoration competing with the line.
 *
 * Gaps are gaps here too. A window nobody reported in is absent from the series
 * the API returns, and the line breaks across it rather than being bridged: a
 * straight segment through a stopped pipeline is a claim that nothing happened,
 * drawn with the same confidence as a measurement.
 */

export interface SeriesPoint {
  t: number;
  v: number | null;
}

const H = 190;
const PAD = { top: 14, right: 12, bottom: 24, left: 46 };

export function TimeSeriesChart({
  points,
  color = "var(--color-accent-mark)",
  unit,
  decimals = 0,
  area = false,
  bucketMinutes,
}: {
  points: SeriesPoint[];
  color?: string;
  unit?: string;
  decimals?: number;
  area?: boolean;
  /** Width of each point, so the tooltip can say what it is reading. */
  bucketMinutes?: number;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const W = 640;

  const measured = useMemo(() => points.filter((p): p is { t: number; v: number } => p.v !== null), [points]);

  if (measured.length < 2) {
    return (
      <p className="flex h-[190px] items-center justify-center text-[12px] text-content-tertiary">
        Not enough readings in this range to plot.
      </p>
    );
  }

  const tMin = points[0].t;
  const tMax = points[points.length - 1].t;
  const vs = measured.map((p) => p.v);
  const rawMin = Math.min(...vs);
  const rawMax = Math.max(...vs);
  const pad = Math.max((rawMax - rawMin) * 0.12, Math.abs(rawMax) * 0.04, 0.5);
  const vMin = Math.max(rawMin - pad, 0);
  const vMax = rawMax + pad;

  const x = (t: number) => PAD.left + ((t - tMin) / Math.max(tMax - tMin, 1)) * (W - PAD.left - PAD.right);
  const y = (v: number) => H - PAD.bottom - ((v - vMin) / Math.max(vMax - vMin, 1)) * (H - PAD.top - PAD.bottom);

  // Split into runs of consecutive readings so a gap breaks the line.
  const runs: { t: number; v: number }[][] = [];
  let run: { t: number; v: number }[] = [];
  points.forEach((p) => {
    if (p.v === null) { if (run.length) runs.push(run); run = []; }
    else run.push({ t: p.t, v: p.v });
  });
  if (run.length) runs.push(run);

  const ticks = [vMin, (vMin + vMax) / 2, vMax];
  const hovered = hover === null ? null : measured[hover];

  const timeLabel = (t: number) => {
    const d = new Date(t);
    // A range spanning more than a day needs the date; inside one it is noise.
    return tMax - tMin > 36 * 3600_000
      ? d.toLocaleDateString([], { day: "numeric", month: "short" })
      : d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  };

  return (
    <div>
      <svg viewBox={`0 0 ${W} ${H}`} className="block w-full" role="img" aria-label="Time series">
        {ticks.map((t) => (
          <g key={t}>
            <line x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)}
                  stroke="var(--color-line-subtle)" strokeWidth={1} />
            <text x={PAD.left - 7} y={y(t) + 3.5} textAnchor="end"
                  className="fill-[var(--color-content-tertiary)] text-[9.5px] tabular">
              {t.toFixed(decimals)}
            </text>
          </g>
        ))}

        {area && runs.map((r, i) =>
          r.length < 2 ? null : (
            <path key={`a${i}`} fill={color} opacity={0.1}
                  d={`M ${x(r[0].t)} ${H - PAD.bottom} ` +
                     r.map((p) => `L ${x(p.t)} ${y(p.v)}`).join(" ") +
                     ` L ${x(r[r.length - 1].t)} ${H - PAD.bottom} Z`} />
          ),
        )}

        {runs.map((r, i) =>
          r.length < 2 ? (
            <circle key={`d${i}`} cx={x(r[0].t)} cy={y(r[0].v)} r={2} fill={color} />
          ) : (
            <path key={`l${i}`} fill="none" stroke={color} strokeWidth={2}
                  strokeLinecap="round" strokeLinejoin="round"
                  d={r.map((p, k) => `${k === 0 ? "M" : "L"} ${x(p.t)} ${y(p.v)}`).join(" ")} />
          ),
        )}

        {hovered && (
          <>
            <line x1={x(hovered.t)} x2={x(hovered.t)} y1={PAD.top} y2={H - PAD.bottom}
                  stroke="var(--color-line-strong)" strokeWidth={1} />
            <circle cx={x(hovered.t)} cy={y(hovered.v)} r={4} fill={color}
                    stroke="var(--color-surface-raised)" strokeWidth={2} />
          </>
        )}

        <text x={PAD.left} y={H - 7} className="fill-[var(--color-content-tertiary)] text-[9.5px]">
          {timeLabel(tMin)}
        </text>
        <text x={W - PAD.right} y={H - 7} textAnchor="end"
              className="fill-[var(--color-content-tertiary)] text-[9.5px]">
          {timeLabel(tMax)}
        </text>

        {/* One hit strip per reading, so the crosshair snaps to real points
            rather than interpolating a value the series does not contain. */}
        {measured.map((p, i) => {
          const half = (W - PAD.left - PAD.right) / Math.max(measured.length * 2, 1);
          return (
            <rect key={`h${p.t}`} x={x(p.t) - half} y={PAD.top}
                  width={Math.max(half * 2, 3)} height={H - PAD.top - PAD.bottom}
                  fill="transparent"
                  onMouseEnter={() => setHover(i)} onMouseLeave={() => setHover(null)} />
          );
        })}
      </svg>

      <div className="mt-1 h-[18px] pl-[46px] text-[11px]">
        {hovered ? (
          <span className="text-content-secondary">
            <span className="tabular font-medium text-content-primary">
              {hovered.v.toFixed(decimals)}
            </span>
            {unit ? ` ${unit}` : ""} · {timeLabel(hovered.t)}
            {bucketMinutes && bucketMinutes > 5 ? ` · ${bucketMinutes}-minute average` : ""}
          </span>
        ) : (
          <span className="text-content-disabled">Hover for values</span>
        )}
      </div>
    </div>
  );
}
