import { describe, expect, it } from "vitest";

import { DATA_DISCLOSURE, DATA_DISCLOSURE_SHORT, describeRisk, TERMS } from "./wording";

/**
 * The words the dashboard uses about itself.
 *
 * The disclosure specs exist because of a real failure: the sentence naming
 * which feeds are real was written out by hand in five files, weather stopped
 * being generated, and four of the five went on saying it was. Every copy was
 * independently plausible, so nothing looked wrong anywhere.
 */
describe("data disclosure", () => {
  it("does not describe a real feed as generated", () => {
    // The exact failure that shipped. Every real feed must appear on the real
    // side of the sentence, and none may be listed among the generated ones.
    //
    // Road speeds joined air and weather here when TomTom's probe feed landed
    // (migration V22). The long form says "air quality" and the short form
    // shortens it to "air", so the assertion matches the word both share — the
    // spec is that the feed is named, not that it is named at one length.
    for (const text of [DATA_DISCLOSURE, DATA_DISCLOSURE_SHORT]) {
      const [real, generated = ""] = text.split(/generated|the rest/i);
      for (const feed of [/\bair\b/i, /weather/i, /road speeds/i]) {
        expect(real).toMatch(feed);
        expect(generated).not.toMatch(feed);
      }
    }
  });

  it("still says something is generated", () => {
    // The opposite failure: a disclosure that quietly stops disclosing. PRD §42
    // requires synthetic data to be labelled, and this sentence is where the
    // product says so in prose.
    //
    // Named by what is still generated rather than by "traffic", which this
    // asserted until traffic stopped being generated and the spec became a
    // record of an old truth. Incidents and city events have no free real-time
    // feed in these cities; if one ever arrives, this fails and is the right
    // place to notice.
    expect(DATA_DISCLOSURE).toMatch(/incidents/i);
    expect(DATA_DISCLOSURE).toMatch(/generated/i);
  });
});

describe("terms", () => {
  it("gives every term a plain label and a definition that is not the label", () => {
    // The point of the pair is that they are two registers. A definition that
    // merely repeats the label has quietly dropped the precise meaning the
    // tooltip exists to carry.
    for (const [key, term] of Object.entries(TERMS)) {
      expect(term.label.length, key).toBeGreaterThan(2);
      expect(term.definition.length, key).toBeGreaterThan(term.label.length + 15);
      expect(term.definition, key).not.toBe(term.label);
    }
  });

  it("keeps jargon out of the labels and inside the definitions", () => {
    // "occupancy ratio" and "composite risk" are the column names. They belong
    // in the definition, where a reader who wants to cite the figure finds
    // them, and not on the surface, where they were the reason the screen
    // needed a glossary.
    expect(TERMS.occupancy.label).not.toMatch(/ratio/i);
    expect(TERMS.occupancy.definition).toMatch(/occupancy ratio/i);
    expect(TERMS.risk.label).not.toMatch(/composite/i);
    expect(TERMS.risk.definition).toMatch(/composite/i);
  });
});

describe("describeRisk", () => {
  it("says what a score means rather than restating it", () => {
    expect(describeRisk(88)).toMatch(/critical/i);
    expect(describeRisk(60)).toMatch(/high/i);
    expect(describeRisk(30)).toMatch(/moderate/i);
    expect(describeRisk(10)).toMatch(/normal/i);
  });

  it("reports an absent score as absent, not as calm", () => {
    // The dangerous rendering: a zone with no reading described as "Normal",
    // which is the whole dashboard's failure mode in one string.
    expect(describeRisk(null)).toBe("No reading");
    expect(describeRisk(null)).not.toMatch(/normal/i);
  });
});
