"use client";

/**
 * A metric's recent shape, at the size of a word.
 *
 * A number without its recent history is unreadable: 127% of capacity is
 * alarming if it was 60% an hour ago and unremarkable if it has been 125% all
 * evening. The dashboard showed the first without the second everywhere.
 *
 * Deliberately unlabelled and unaxised. A sparkline's job is shape — rising,
 * falling, spiking, flat — read in the same glance as the number beside it. Ticks
 * and gridlines at this size are noise, and anyone who needs the values has the
 * full chart a click away.
 *
 * Gaps are gaps. A window with no reading breaks the line rather than being
 * interpolated across, because a straight segment drawn through missing data is
 * a claim about what happened while the feed was down.
 */

export function Sparkline({
  points,
  width = 84,
  height = 26,
  stroke = "var(--color-accent-mark)",
  fill = true,
  ariaLabel,
}: {
  /** Chronological. Null marks a window that reported nothing. */
  points: (number | null)[];
  width?: number;
  height?: number;
  stroke?: string;
  fill?: boolean;
  ariaLabel?: string;
}) {
  const measured = points.filter((p): p is number => p !== null);
  if (measured.length < 2) {
    return (
      <div
        style={{ width, height }}
        className="flex items-center text-[10px] text-content-disabled"
        aria-label={ariaLabel}
      >
        not enough history
      </div>
    );
  }

  const min = Math.min(...measured);
  const max = Math.max(...measured);
  // A flat series would divide by zero and, drawn to full height, would look
  // like a dramatic move. Give it a band and it sits mid-height, which is what
  // "nothing changed" should look like.
  const span = max - min || Math.max(Math.abs(max) * 0.1, 1);

  const pad = 2;
  const x = (i: number) => (i / (points.length - 1)) * (width - pad * 2) + pad;
  const y = (v: number) => height - pad - ((v - min) / span) * (height - pad * 2);

  // Split on gaps so a missing window breaks the line instead of being bridged.
  const segments: { i: number; v: number }[][] = [];
  let current: { i: number; v: number }[] = [];
  points.forEach((v, i) => {
    if (v === null) {
      if (current.length) segments.push(current);
      current = [];
    } else {
      current.push({ i, v });
    }
  });
  if (current.length) segments.push(current);

  const last = measured[measured.length - 1];
  const lastIndex = points.length - 1 - [...points].reverse().findIndex((p) => p !== null);

  return (
    <svg
      width={width}
      height={height}
      viewBox={`0 0 ${width} ${height}`}
      role="img"
      aria-label={ariaLabel ?? "Recent trend"}
      className="block overflow-visible"
    >
      {fill && segments.map((segment, s) =>
        segment.length < 2 ? null : (
          <path
            key={`fill-${s}`}
            d={
              `M ${x(segment[0].i)} ${height - pad} ` +
              segment.map((p) => `L ${x(p.i)} ${y(p.v)}`).join(" ") +
              ` L ${x(segment[segment.length - 1].i)} ${height - pad} Z`
            }
            fill={stroke}
            opacity={0.12}
          />
        ),
      )}

      {segments.map((segment, s) =>
        segment.length < 2 ? (
          // A lone reading between two gaps is a dot, not a line — there is no
          // direction to draw.
          <circle key={`dot-${s}`} cx={x(segment[0].i)} cy={y(segment[0].v)} r={1.5} fill={stroke} />
        ) : (
          <path
            key={`line-${s}`}
            d={segment.map((p, k) => `${k === 0 ? "M" : "L"} ${x(p.i)} ${y(p.v)}`).join(" ")}
            fill="none"
            stroke={stroke}
            strokeWidth={1.5}
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        ),
      )}

      {/* The latest reading, so the eye lands on "now" rather than on the peak. */}
      <circle cx={x(lastIndex)} cy={y(last)} r={2} fill={stroke} />
    </svg>
  );
}

/**
 * Change between the first and last measured points, as a signed percentage.
 *
 * Returns null when there is nothing to compare — fewer than two readings, or a
 * baseline of zero, where a percentage change is undefined rather than infinite.
 */
export function trendOf(points: (number | null)[]): number | null {
  const measured = points.filter((p): p is number => p !== null);
  if (measured.length < 2) return null;
  const first = measured[0];
  const last = measured[measured.length - 1];
  if (first === 0) return null;
  return ((last - first) / Math.abs(first)) * 100;
}

/**
 * A trend as an arrow and a percentage.
 *
 * `higherIsWorse` exists because the same arrow means opposite things: rising
 * congestion is bad, rising average speed is good. Colouring by direction alone
 * would paint a recovering city red.
 */
export function TrendBadge({
  change,
  higherIsWorse = true,
  className,
}: {
  change: number | null;
  higherIsWorse?: boolean;
  className?: string;
}) {
  if (change === null) {
    return <span className={`text-[10px] text-content-disabled ${className ?? ""}`}>no trend yet</span>;
  }

  // Below this, a reading is noise rather than movement, and an arrow would
  // invite a decision the data does not support.
  const FLAT = 1.5;
  const flat = Math.abs(change) < FLAT;
  const worse = higherIsWorse ? change > 0 : change < 0;

  const tone = flat
    ? "text-content-tertiary"
    : worse
      ? "text-status-high"
      : "text-status-normal";

  return (
    <span className={`inline-flex items-center gap-0.5 text-[10px] font-medium tabular ${tone} ${className ?? ""}`}>
      <span aria-hidden="true">{flat ? "→" : change > 0 ? "↑" : "↓"}</span>
      {flat ? "steady" : `${Math.abs(change).toFixed(0)}%`}
    </span>
  );
}
