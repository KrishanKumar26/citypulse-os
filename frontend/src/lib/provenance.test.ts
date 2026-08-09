import { describe, expect, it } from "vitest";

import { PROVENANCE_LABEL, describeAqi, provenanceLevel } from "./provenance";

/**
 * The three words a reader uses to decide how much to trust a number.
 *
 * These assertions look trivial and are not. The failure they guard against is
 * a rename or a refactor collapsing two provenances into one label — at which
 * point the dashboard still renders, still shows an AQI, and has silently
 * stopped distinguishing an instrument's reading from a generated one.
 */
describe("air provenance", () => {
  it("gives each provenance its own word", () => {
    const labels = Object.values(PROVENANCE_LABEL);
    expect(new Set(labels).size).toBe(labels.length);
  });

  it("never describes a real reading as synthetic", () => {
    expect(describeAqi(192, "MODERATE", "MEASURED")).toContain("measured");
    expect(describeAqi(192, "MODERATE", "MODELLED")).toContain("modelled");
    expect(describeAqi(192, "MODERATE", "MEASURED")).not.toContain("synthetic");
    // "modelled" must not be read as a substring win for "measured" either.
    expect(describeAqi(192, "MODERATE", "MODELLED")).not.toContain("measured");
  });

  it("says nothing was measured rather than reporting a provenance for no reading", () => {
    // The dangerous rendering is "0 (GOOD) · synthetic": a window with no AQI
    // has no provenance, and inventing one would report the absence of air
    // quality data as clean air that happens to be generated.
    expect(describeAqi(null, null, null)).toBe("Not measured");
    expect(describeAqi(null, null, "SYNTHETIC")).toBe("Not measured");
  });

  it("keeps the number and its band when provenance is unknown", () => {
    // A reading whose provenance the API did not send is still a reading. It
    // renders without a claim about where it came from rather than defaulting
    // to one.
    expect(describeAqi(88, "SATISFACTORY", null)).toBe("88 (SATISFACTORY)");
  });

  it("marks only the generated feed", () => {
    // Measured and modelled are both real. Colouring one of them would suggest
    // a reader should discount it, and the point of the third state is that
    // they should not.
    expect(provenanceLevel("SYNTHETIC")).toBe("moderate");
    expect(provenanceLevel("MEASURED")).toBe("neutral");
    expect(provenanceLevel("MODELLED")).toBe("neutral");
  });
});
