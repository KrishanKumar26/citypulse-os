/**
 * Display formatting. Units are metric throughout (docs/DEVELOPMENT_PLAN.md,
 * Phase 0 assumptions).
 */

const NUMBER_FORMAT = new Intl.NumberFormat("en-GB");

export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) return "—";
  return NUMBER_FORMAT.format(value);
}

/** Areas arrive as decimal strings so the backend's precision is not lost to float. */
export function formatArea(areaSqKm: string | number | null | undefined): string {
  if (areaSqKm === null || areaSqKm === undefined) return "—";
  const value = Number(areaSqKm);
  if (Number.isNaN(value)) return "—";
  return `${value.toFixed(1)} km²`;
}

/**
 * Renders a UTC instant in a city's timezone. Metrics are stored in UTC and
 * displayed locally to the city under observation, not to the viewer — an
 * operator in one timezone reasoning about another city needs the city's clock.
 */
export function formatInstant(iso: string | null | undefined, timezone?: string): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";

  return new Intl.DateTimeFormat("en-GB", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: timezone,
  }).format(date);
}

/** Coarse relative time for recency, e.g. "4 min ago". */
export function formatRelative(iso: string | null | undefined): string {
  if (!iso) return "—";
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) return "—";

  const seconds = Math.floor((Date.now() - date.getTime()) / 1000);
  if (seconds < 60) return "just now";
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`;
  if (seconds < 86_400) return `${Math.floor(seconds / 3600)} h ago`;
  return `${Math.floor(seconds / 86_400)} d ago`;
}
