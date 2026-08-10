import { describe, expect, it } from "vitest";

import { describeSituation } from "./situation-language";

/**
 * The wording a citizen reads on a Command Center card.
 *
 * These specs guard two opposite failures. One is the card going back to
 * engineering language — a reader who does not work here should never meet
 * "occupancy ratio" or "deviation" in the default view. The other is this
 * module quietly acquiring authority it does not have: it decides phrasing from
 * figures the detection already established, and must never introduce a number
 * of its own.
 */

const CITIZEN_VIEW = (metric: string, observed: number, baseline: number) => {
  const c = describeSituation(metric, observed, baseline);
  return [c.title, c.headline, c.reading, c.usual, c.meaning, c.guidance].join(" ");
};

describe("describeSituation", () => {
  it("says what is happening without a term of art", () => {
    const text = CITIZEN_VIEW("vehicle_count", 1047, 190).toLowerCase();
    for (const jargon of [
      "occupancy", "deviation", "baseline", "composite", "anomaly",
      "percentile", "ratio", "sample",
    ]) {
      expect(text, jargon).not.toContain(jargon);
    }
  });

  it("matches the units the card shows, not the units the pipeline stores", () => {
    // occupancy_ratio is stored as 1.62 and shown as 162%. The sentence and the
    // figure beside it came from the same source and disagreed once already.
    const copy = describeSituation("occupancy_ratio", 1.62, 0.53);
    expect(copy.reading).toContain("162%");
    expect(copy.usual).toContain("53%");
    expect(copy.reading).not.toContain("1.62");
  });

  it("turns direction into the right situation, not just the right number", () => {
    // Speed below normal is congestion; speed above normal is not a problem.
    // A card that called both "Slow Traffic" would be worse than no card.
    expect(describeSituation("average_speed_kph", 15.7, 42.8).title).toBe("Slow Traffic");
    expect(describeSituation("average_speed_kph", 77, 42.8).title).toBe("Free-Flowing Traffic");
    expect(describeSituation("vehicle_count", 1047, 190).title).toBe("Heavy Traffic");
    expect(describeSituation("vehicle_count", 20, 190).title).toBe("Unusually Quiet");
  });

  it("scales its language to how far from normal the reading is", () => {
    expect(describeSituation("vehicle_count", 1047, 190).headline).toContain("much higher");
    expect(describeSituation("vehicle_count", 200, 190).headline).toContain("slightly higher");
  });

  it("says something different when the roads are past capacity", () => {
    // Above 1.0 is not just a worse number: demand exceeds what the road was
    // built to carry, and the citizen sentence should say so.
    const over = describeSituation("occupancy_ratio", 1.62, 0.53);
    const under = describeSituation("occupancy_ratio", 0.9, 0.53);
    expect(over.meaning).toContain("built to carry");
    expect(under.meaning).not.toContain("built to carry");
  });

  it("suggests nothing to do when nothing is wrong", () => {
    expect(describeSituation("average_speed_kph", 77, 42.8).guidance).toBe("No action needed.");
    expect(describeSituation("risk_score", 10, 40).guidance).toBe("No action needed.");
  });

  it("admits ignorance of a metric it has not been taught", () => {
    // The dangerous failure: inventing what an unknown measure means on the
    // ground. It may state the reading and the direction; it may not interpret.
    const copy = describeSituation("flood_depth_m", 3, 1);
    expect(copy.reading).toContain("3.00");
    expect(copy.meaning).toBe("This zone is behaving unlike itself for this time of day.");
    expect(copy.meaning.toLowerCase()).not.toContain("flood");
  });

  it("does not divide by a baseline of zero", () => {
    const copy = describeSituation("vehicle_count", 500, 0);
    expect(copy.reading).toContain("500");
    expect(copy.headline).not.toContain("Infinity");
    expect(copy.headline).not.toContain("NaN");
  });
});
