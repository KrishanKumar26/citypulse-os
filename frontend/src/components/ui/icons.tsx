/**
 * The product's line icons, in one place.
 *
 * These lived inside Sidebar as a private `NavIcon`, which was fine while the
 * sidebar was the only thing drawing them. The situation cards need the gear
 * and the chevron, and a second copy of a twenty-line SVG path is how two
 * icon sets start diverging.
 *
 * One geometry for all of them: a 24-unit box, 1.6 stroke, round caps, no fill.
 * An icon added later has to match that or it will read as borrowed from
 * somewhere else.
 */

export type GlyphName =
  | "grid" | "activity" | "pulse" | "trending" | "beaker" | "sparkle"
  | "layers" | "check" | "target" | "bell" | "chart" | "shield"
  | "database" | "key" | "settings" | "chevron"
  | "car" | "gauge" | "wind" | "droplet" | "warning";

const PATHS: Record<GlyphName, string> = {
  grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
  activity: "M3 12h4l3-8 4 16 3-8h4",
  pulse: "M3 12h3l2-4 3 9 3-14 2 9h5",
  trending: "M3 17l6-6 4 4 8-8M17 7h4v4",
  beaker: "M9 3v6L4 19a2 2 0 002 2h12a2 2 0 002-2l-5-10V3M8 3h8",
  sparkle: "M12 3l2 5 5 2-5 2-2 5-2-5-5-2 5-2z",
  layers: "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5",
  check: "M4 12.5l5 5L20 6.5",
  target: "M12 21a9 9 0 100-18 9 9 0 000 18zM12 16a4 4 0 100-8 4 4 0 000 8zM12 13a1 1 0 100-2 1 1 0 000 2z",
  bell: "M18 8a6 6 0 10-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 01-3.4 0",
  chart: "M3 3v18h18M8 16V10M13 16V6M18 16v-4",
  shield: "M12 3l8 3v6c0 5-3.4 8.4-8 9-4.6-.6-8-4-8-9V6zM9 12l2 2 4-4",
  database: "M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6",
  key: "M15 7a4 4 0 11-4 4l-7 7v3h3l7-7",
  settings:
    "M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.9-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1A1.7 1.7 0 008.9 19a1.7 1.7 0 00-1.9.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.9 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1A1.7 1.7 0 004.6 8.9a1.7 1.7 0 00-.3-1.9l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.9-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z",
  chevron: "M6 9l6 6 6-6",

  // Situation icons. One per kind of thing a card can be about, so a reader
  // scanning a column of cards can tell traffic from air without reading.
  car: "M5 17h14M6.5 17v-4l1.6-4.4A2 2 0 0110 7.2h4a2 2 0 011.9 1.4L17.5 13v4M8 20a1 1 0 100-2 1 1 0 000 2zM16 20a1 1 0 100-2 1 1 0 000 2z",
  gauge: "M12 14l3.5-3.5M4.5 18a9 9 0 1115 0",
  wind: "M3 8h11a3 3 0 10-3-3M3 12h15a3 3 0 11-3 3M3 16h8",
  droplet: "M12 3s6 6.4 6 10a6 6 0 11-12 0c0-3.6 6-10 6-10z",
  warning:
    "M12 10v4M12 17.5h.01M10.3 4.3L2.6 18a2 2 0 001.7 3h15.4a2 2 0 001.7-3L13.7 4.3a2 2 0 00-3.4 0z",
};

export function Glyph({
  name,
  size = 15,
  className,
}: {
  name: GlyphName;
  size?: number;
  className?: string;
}) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      // Decorative wherever it is used: every icon here sits beside text that
      // already says the same thing, so announcing it would repeat the label.
      aria-hidden="true"
      focusable="false"
    >
      <path d={PATHS[name]} />
    </svg>
  );
}
