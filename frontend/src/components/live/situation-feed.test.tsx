import { describe, expect, it } from "vitest";

import type { ZoneOutlook } from "@/lib/api/types";

import { outlookFor } from "./SituationFeed";

/**
 * The pairing rule for a forecast and an anomaly.
 *
 * The city outlook forecasts exactly one metric — whichever the model was
 * trained for — and every zone in it carries a value for that metric alone.
 * Shown beside an anomaly on a different measure, an occupancy ratio of 0.61
 * rendered as "0.6 / 100" under a risk row: the platform appearing to predict
 * that a zone at 30 of 100 was about to fall to nearly zero, in every row, on
 * the first screen after sign-in.
 *
 * Extracted as a function so the rule is testable rather than living inside a
 * render. The failure it guards against is silent — the wrong number is
 * well-formed, correctly coloured, and about the wrong quantity.
 */
const zoneOutlook = { zoneId: "z1", predictedValue: "0.61" } as ZoneOutlook;
const byZone = new Map([["z1", zoneOutlook]]);

describe("pairing a forecast with an anomaly", () => {
  it("shows the forecast when it is of the same measure", () => {
    const paired = outlookFor(
      { metric: "occupancy_ratio", zoneId: "z1" },
      { targetMetric: "occupancy_ratio" },
      byZone,
    );
    expect(paired).toBe(zoneOutlook);
  });

  it("shows nothing when the forecast is of a different measure", () => {
    // The bug exactly: a risk anomaly beside an occupancy forecast.
    const paired = outlookFor(
      { metric: "risk_score", zoneId: "z1" },
      { targetMetric: "occupancy_ratio" },
      byZone,
    );
    expect(paired).toBeUndefined();
  });

  it("shows nothing when no forecast has been issued at all", () => {
    expect(outlookFor({ metric: "risk_score", zoneId: "z1" }, undefined, byZone))
      .toBeUndefined();
  });

  it("shows nothing when this zone is absent from the forecast", () => {
    // A zone the model declined to forecast is not a zone forecast at zero.
    const paired = outlookFor(
      { metric: "occupancy_ratio", zoneId: "z2" },
      { targetMetric: "occupancy_ratio" },
      byZone,
    );
    expect(paired).toBeUndefined();
  });
});
