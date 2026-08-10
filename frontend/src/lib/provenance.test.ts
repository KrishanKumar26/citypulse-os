import { describe, expect, it } from "vitest";

import {
  PROVENANCE_EXACT,
  PROVENANCE_LABEL,
  PROVENANCE_MARK,
  describeAqi,
  provenanceLevel,
  provenanceTooltip,
} from "./provenance";

/**
 * The words a reader uses to decide how much to trust a number.
 *
 * These assertions look trivial and are not. The failure they guard against is
 * a rename or a refactor collapsing two provenances into one label — at which
 * point the dashboard still renders, still shows an AQI, and has silently
 * stopped distinguishing an instrument's reading from a generated one.
 *
 * They assert the distinction, not the wording. The words moved from the exact
 * term to a plain phrase and these tests should not have to be rewritten for
 * the next such change — only for one that loses a difference.
 */
describe("air provenance", () => {
  it("gives each provenance its own words", () => {
    for (const words of [PROVENANCE_LABEL, PROVENANCE_MARK, PROVENANCE_EXACT]) {
      const values = Object.values(words);
      expect(new Set(values).size).toBe(values.length);
    }
  });

  it("keeps a sensor and a model apart in the compact form", () => {
    // This is the bug that shipped: the table mark was the label's first
    // letter, and "measured" and "modelled" share it. Sixty-two rows rendered
    // an instrument and a model identically, in the one column built to tell
    // them apart. A collision here is invisible in the UI — both states look
    // fine, they just stop being two states.
    expect(PROVENANCE_MARK.MEASURED).not.toBe(PROVENANCE_MARK.MODELLED);
    expect(PROVENANCE_MARK.MEASURED.charAt(0)).not.toBe(PROVENANCE_MARK.MODELLED.charAt(0));
  });

  it("never describes a real reading as generated", () => {
    const measured = describeAqi(192, "MODERATE", "MEASURED");
    const modelled = describeAqi(192, "MODERATE", "MODELLED");
    expect(measured).toBe(`192 (MODERATE) · ${PROVENANCE_LABEL.MEASURED}`);
    expect(modelled).toBe(`192 (MODERATE) · ${PROVENANCE_LABEL.MODELLED}`);
    expect(measured).not.toContain(PROVENANCE_LABEL.SYNTHETIC);
    expect(modelled).not.toContain(PROVENANCE_LABEL.SYNTHETIC);
    // Neither may read as the other, including as a substring.
    expect(measured).not.toContain(PROVENANCE_LABEL.MODELLED);
    expect(modelled).not.toContain(PROVENANCE_LABEL.MEASURED);
  });

  it("reports no reading rather than inventing a provenance for one", () => {
    // The dangerous rendering is "0 (GOOD) · demo data": a window with no AQI
    // has no provenance, and inventing one would report the absence of air
    // quality data as clean air that happens to be generated.
    expect(describeAqi(null, null, null)).toBe("No reading");
    expect(describeAqi(null, null, "SYNTHETIC")).toBe("No reading");
  });

  it("keeps the number and its band when provenance is unknown", () => {
    // A reading whose provenance the API did not send is still a reading. It
    // renders without a claim about where it came from rather than defaulting
    // to one.
    expect(describeAqi(88, "SATISFACTORY", null)).toBe("88 (SATISFACTORY)");
  });

  it("puts the exact term in reach without putting it on screen", () => {
    // The plain phrase is what a reader understands; the exact term is what
    // appears in the API, the database and the docs. Losing the second would
    // leave someone who wants to cite the figure with nothing to search for.
    for (const source of ["MEASURED", "MODELLED", "SYNTHETIC"] as const) {
      expect(provenanceTooltip(source)).toContain(PROVENANCE_EXACT[source]);
      expect(provenanceTooltip(source).length).toBeGreaterThan(
        PROVENANCE_EXACT[source].length + 10,
      );
      // And the exact term stays off the surface, where it read as jargon.
      expect(PROVENANCE_LABEL[source]).not.toContain(PROVENANCE_EXACT[source]);
    }
  });

  it("marks only the generated feed", () => {
    // A sensor's reading and a model's are both real. Colouring one of them
    // would suggest a reader should discount it, and the point of the third
    // state is that they should not.
    expect(provenanceLevel("SYNTHETIC")).toBe("moderate");
    expect(provenanceLevel("MEASURED")).toBe("neutral");
    expect(provenanceLevel("MODELLED")).toBe("neutral");
  });
});
