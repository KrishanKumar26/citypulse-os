"use client";

import { useMemo, useState } from "react";
import type { ForecastPoint, ZoneHistoryPoint } from "@/lib/api/types";

/**
 * What was observed, what is predicted, and how sure the model is.
 *
 * The forecast page showed a table of horizons. A table can be read but not
 * *seen*: whether the prediction continues the trend or breaks it, and whether
 * the uncertainty is narrow enough for the prediction to mean anything, are
 * shape questions, and rows of numbers answer them slowly or not at all.
 *
 * Three encodings, in the order they should be read:
 *
 *   observed      a solid line, ending at now
 *   predicted     a dashed line continuing from it
 *   uncertainty   the band between the model's own bounds
 *
 * The band is not decoration. A prediction of 149% means one thing when the
 * bounds are 145-153 and something else entirely when they are 110-190, and the
 * single number reads identically in both cases. Drawing the bounds makes an
 * over-confident reading of a wide interval impossible.
 *
 * Observed and predicted are drawn in cyan and fuchsia. That pair was chosen by
 * measurement — it separates by ΔE 32 in normal vision and 10 under
 * deuteranopia — because the one distinction this chart cannot afford to blur is
 * which part already happened.
 */

interface Props {
  history: ZoneHistoryPoint[];
  horizons: ForecastPoint[];
  /** Reads the plotted value out of a history point, matching the forecast metric. */
  readHistory: (point: ZoneHistoryPoint) => number | null;
  /** Multiplies stored values for display — ratios are shown as percentages. */
  scale?: number;
  unit: string;
  decimals: number;
}

const W = 720;
const H = 240;
const PAD = { top: 16, right: 16, bottom: 26, left: 44 };

export function ForecastChart({ history, horizons, readHistory, scale = 1, unit, decimals }: Props) {
  const [hover, setHover] = useState<number | null>(null);

  const series = useMemo(() => {
    const observed = history
      .map((p) => ({ t: new Date(p.windowStart).getTime(), v: readHistory(p) }))
      .filter((p) => Number.isFinite(p.t))
      .sort((a, b) => a.t - b.t)
      .map((p) => ({ ...p, v: p.v === null ? null : p.v * scale }));

    const predicted = horizons
      .map((h) => ({
        t: new Date(h.targetTime).getTime(),
        v: Number(h.predictedValue) * scale,
        lo: h.lowerBound === null ? null : Number(h.lowerBound) * scale,
        hi: h.upperBound === null ? null : Number(h.upperBound) * scale,
        confidence: Number(h.confidence),
        horizonMinutes: h.horizonMinutes,
      }))
      .sort((a, b) => a.t - b.t);

    return { observed, predicted };
  }, [history, horizons, readHistory, scale]);

  const { observed, predicted } = series;
  const lastObserved = [...observed].reverse().find((p) => p.v !== null) ?? null;

  if (observed.filter((p) => p.v !== null).length < 2 && predicted.length === 0) {
    return (
      <p className="px-5 py-10 text-center text-[13px] text-content-tertiary">
        Not enough history or forecast to plot yet.
      </p>
    );
  }

  // A shared domain across both series and the band. Two scales for two lines on
  // one plot is the classic way to make an unrelated pair look correlated.
  const times = [...observed.map((p) => p.t), ...predicted.map((p) => p.t)];
  const values = [
    ...observed.map((p) => p.v),
    ...predicted.flatMap((p) => [p.v, p.lo, p.hi]),
  ].filter((v): v is number => v !== null);

  const tMin = Math.min(...times);
  const tMax = Math.max(...times);
  const vMinRaw = Math.min(...values);
  const vMaxRaw = Math.max(...values);
  // A little headroom, and never a zero-height domain for a flat series.
  const padV = Math.max((vMaxRaw - vMinRaw) * 0.12, Math.abs(vMaxRaw) * 0.05, 1);
  const vMin = Math.max(vMinRaw - padV, 0);
  const vMax = vMaxRaw + padV;

  const x = (t: number) => PAD.left + ((t - tMin) / Math.max(tMax - tMin, 1)) * (W - PAD.left - PAD.right);
  const y = (v: number) => H - PAD.bottom - ((v - vMin) / Math.max(vMax - vMin, 1)) * (H - PAD.top - PAD.bottom);

  const line = (pts: { t: number; v: number | null }[]) => {
    // Split at gaps: a straight segment across a window that reported nothing is
    // a claim about what happened while the feed was down.
    const out: string[] = [];
    let open = false;
    pts.forEach((p) => {
      if (p.v === null) { open = false; return; }
      out.push(`${open ? "L" : "M"} ${x(p.t).toFixed(1)} ${y(p.v).toFixed(1)}`);
      open = true;
    });
    return out.join(" ");
  };

  // The band, and the prediction line, both start from the last observation so
  // the forecast visibly continues the history rather than floating beside it.
  const bandPoints = lastObserved && lastObserved.v !== null
    ? [{ t: lastObserved.t, lo: lastObserved.v, hi: lastObserved.v }, ...predicted.map((p) => ({ t: p.t, lo: p.lo, hi: p.hi }))]
    : predicted.map((p) => ({ t: p.t, lo: p.lo, hi: p.hi }));

  const banded = bandPoints.filter((p): p is { t: number; lo: number; hi: number } => p.lo !== null && p.hi !== null);
  const bandPath = banded.length >= 2
    ? `M ${banded.map((p) => `${x(p.t).toFixed(1)} ${y(p.hi).toFixed(1)}`).join(" L ")} ` +
      `L ${[...banded].reverse().map((p) => `${x(p.t).toFixed(1)} ${y(p.lo).toFixed(1)}`).join(" L ")} Z`
    : null;

  const predictedLine = lastObserved && lastObserved.v !== null
    ? [{ t: lastObserved.t, v: lastObserved.v }, ...predicted.map((p) => ({ t: p.t, v: p.v }))]
    : predicted.map((p) => ({ t: p.t, v: p.v }));

  const ticks = [vMin, (vMin + vMax) / 2, vMax];
  const hovered = hover === null ? null : predicted[hover];

  return (
    <div className="px-5 pb-4">
      <svg
        viewBox={`0 0 ${W} ${H}`}
        className="block w-full"
        role="img"
        aria-label="Observed values and the forecast that continues them, with the model's confidence interval"
      >
        {/* Recessive horizontal reference lines only — vertical gridlines would
            compete with the boundary between observed and predicted. */}
        {ticks.map((t) => (
          <g key={t}>
            <line
              x1={PAD.left} x2={W - PAD.right} y1={y(t)} y2={y(t)}
              stroke="var(--color-line-subtle)" strokeWidth={1}
            />
            <text
              x={PAD.left - 8} y={y(t) + 3.5} textAnchor="end"
              className="fill-[var(--color-content-tertiary)] text-[10px] tabular"
            >
              {t.toFixed(decimals)}
            </text>
          </g>
        ))}

        {bandPath && (
          <path d={bandPath} fill="var(--color-ai)" opacity={0.14} />
        )}

        {/* The boundary between what happened and what is guessed. */}
        {lastObserved && (
          <line
            x1={x(lastObserved.t)} x2={x(lastObserved.t)}
            y1={PAD.top} y2={H - PAD.bottom}
            stroke="var(--color-line-strong)" strokeWidth={1} strokeDasharray="3 3"
          />
        )}

        <path d={line(observed)} fill="none" stroke="var(--color-accent-mark)" strokeWidth={2}
              strokeLinecap="round" strokeLinejoin="round" />
        <path d={line(predictedLine)} fill="none" stroke="var(--color-ai)" strokeWidth={2}
              strokeDasharray="5 4" strokeLinecap="round" strokeLinejoin="round" />

        {predicted.map((p, i) => (
          <circle
            key={p.t}
            cx={x(p.t)} cy={y(p.v)} r={hover === i ? 5 : 3.5}
            fill="var(--color-ai)"
            stroke="var(--color-surface-raised)" strokeWidth={2}
          />
        ))}

        {/* Hit strips, one per prediction, wider than the marks they select. */}
        {predicted.map((p, i) => {
          const half = (W - PAD.left - PAD.right) / Math.max(predicted.length * 2, 1);
          return (
            <rect
              key={`hit-${p.t}`}
              x={x(p.t) - half} y={PAD.top} width={half * 2} height={H - PAD.top - PAD.bottom}
              fill="transparent"
              onMouseEnter={() => setHover(i)}
              onMouseLeave={() => setHover(null)}
            />
          );
        })}

        {lastObserved && (
          <text
            x={x(lastObserved.t) + 5} y={PAD.top + 10}
            className="fill-[var(--color-content-tertiary)] text-[10px]"
          >
            now
          </text>
        )}
      </svg>

      {/* Legend. Two series always carry one — identity must not rest on colour. */}
      <div className="mt-1 flex flex-wrap items-center gap-x-5 gap-y-1.5 pl-[44px]">
        <LegendKey color="var(--color-accent-mark)" label="Observed" />
        <LegendKey color="var(--color-ai)" label="Predicted" dashed />
        <LegendKey color="var(--color-ai)" label="Confidence interval" band />
      </div>

      {hovered && (
        <div className="mt-3 rounded-md border border-line-default bg-surface-inset px-3 py-2 text-[12px]">
          <span className="font-medium text-content-primary tabular">
            {hovered.v.toFixed(decimals)} {unit}
          </span>
          <span className="text-content-tertiary">
            {" "}in {hovered.horizonMinutes} min
            {hovered.lo !== null && hovered.hi !== null && (
              <> · between {hovered.lo.toFixed(decimals)} and {hovered.hi.toFixed(decimals)}</>
            )}
            {" "}· {(hovered.confidence * 100).toFixed(0)}% confidence
          </span>
        </div>
      )}
    </div>
  );
}

function LegendKey({ color, label, dashed, band }: { color: string; label: string; dashed?: boolean; band?: boolean }) {
  return (
    <span className="flex items-center gap-1.5 text-[11px] text-content-secondary">
      {band ? (
        <span aria-hidden="true" className="h-2.5 w-4 rounded-sm" style={{ background: color, opacity: 0.25 }} />
      ) : (
        <svg width="16" height="8" aria-hidden="true">
          <line
            x1="0" y1="4" x2="16" y2="4"
            stroke={color} strokeWidth={2} strokeLinecap="round"
            strokeDasharray={dashed ? "4 3" : undefined}
          />
        </svg>
      )}
      {label}
    </span>
  );
}
