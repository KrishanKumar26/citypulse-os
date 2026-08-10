/**
 * The words this product uses for its own measurements.
 *
 * Every term below was, until now, the name of the database column it comes
 * from. That is the right name for a schema and the wrong one for a screen:
 * "occupancy ratio" is exact and means nothing to a reader who has not seen the
 * table, and a dashboard whose labels have to be looked up is a dashboard that
 * gets misread rather than one that gets studied.
 *
 * Each entry pairs the plain phrase with the precise definition. The screen
 * shows the phrase; the definition sits in a `title` on the same element, so
 * nothing is lost and nothing has to be guessed. Where the exact term is worth
 * keeping — because it appears in the API or the docs and a reader may need to
 * search for it — it opens the definition.
 *
 * Centralised for the same reason the provenance words are: eleven screens use
 * these, and a term that means one thing on the map and another in the table is
 * worse than a technical term used consistently.
 */

export interface Term {
  /** What the reader sees. No jargon, no glossary needed. */
  label: string;
  /** What it means, shown on hover. Opens with the exact term where one exists. */
  definition: string;
}

export const TERMS = {
  occupancy: {
    label: "how full the roads are",
    definition:
      "Occupancy ratio — vehicles present over the road's rated capacity. Above " +
      "1.0 means more traffic than the road was built for.",
  },
  risk: {
    label: "overall risk",
    definition:
      "Composite risk, 0 to 100 — traffic, air quality, rainfall and active " +
      "incidents combined into one figure for the zone.",
  },
  window: {
    label: "as of",
    definition:
      "The five-minute window these values were computed from. Every figure on " +
      "this screen comes from one, so it can be pointed at.",
  },
  samples: {
    label: "readings behind this",
    definition:
      "Sample count — how many raw readings this window was computed from. A low " +
      "count means a thin sample, not a precise one.",
  },
  congestion: {
    label: "how backed up traffic is",
    definition:
      "Congestion level — normal, moderate, high or critical, from the share of " +
      "road capacity in use.",
  },
  speed: {
    label: "average speed",
    definition: "Mean speed across the vehicles observed in this window.",
  },
  baseline: {
    label: "what is normal here",
    definition:
      "The learned baseline — what this zone usually does at this hour of the " +
      "week, from weeks of its own history. Not a fixed threshold.",
  },
  confidence: {
    label: "how much to trust it",
    definition:
      "Computed from how far this model's past forecasts landed from what " +
      "actually happened, on data it was not trained on.",
  },
} as const satisfies Record<string, Term>;

export type TermKey = keyof typeof TERMS;

/** `title` text for a term: the plain label is on screen, this explains it. */
export function define(key: TermKey): string {
  return TERMS[key].definition;
}

/**
 * What a risk score means, in words.
 *
 * A reader who sees 68 has no idea whether that is a normal Tuesday or a reason
 * to send someone. The bands are the same ones the pipeline uses to set
 * `risk_level`, so this names the number the platform already assigned rather
 * than introducing a second opinion about it.
 */
export function describeRisk(score: number | null): string {
  if (score === null) return "No reading";
  if (score >= 75) return "Critical — needs attention now";
  if (score >= 50) return "High — worth watching";
  if (score >= 25) return "Moderate — normal for a busy period";
  return "Normal";
}

/** "No reading" rather than "Not measured" — shorter, and true of every signal. */
export const NO_READING = "No reading";
