import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

/**
 * One severity vocabulary, enforced.
 *
 * The product had three. `globals.css` defined the status scale, the chart drew
 * from it, and `ZoneMap` carried four hardcoded hexes that matched neither — so
 * a zone marked HIGH was `#db6d28` on the map and `#e07a3c` in the badge printed
 * beside it, for the same word about the same zone. Nothing failed; the colours
 * simply drifted apart over four phases, and the only way to notice was to hold
 * two parts of the screen side by side.
 *
 * The map now resolves the tokens at runtime, so the literals it ships are only
 * a pre-hydration fallback. This asserts the fallback still matches, because a
 * fallback that has quietly gone stale is exactly the bug this replaced.
 */

const ROOT = join(__dirname, "..");

function tokenValue(name: string): string {
  const css = readFileSync(join(ROOT, "app", "globals.css"), "utf8");
  const match = css.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{3,8})`));
  if (!match) throw new Error(`token ${name} is not defined in globals.css`);
  return match[1].toLowerCase();
}

/** The same token as redefined by the light theme block. */
function lightTokenValue(name: string): string {
  const css = readFileSync(join(ROOT, "app", "globals.css"), "utf8");
  const block = css.split('html[data-theme="light"] {')[1];
  if (!block) throw new Error("the light theme block is missing from globals.css");
  const match = block.match(new RegExp(`${name}:\\s*(#[0-9a-fA-F]{3,8})`));
  if (!match) throw new Error(`token ${name} is not overridden for the light theme`);
  return match[1].toLowerCase();
}

function mapFallback(level: string): string {
  const src = readFileSync(join(ROOT, "components", "map", "ZoneMap.tsx"), "utf8");
  const block = src.match(/FALLBACK_CONDITION_COLORS[^{]*\{([^}]*)\}/);
  if (!block) throw new Error("ZoneMap no longer declares FALLBACK_CONDITION_COLORS");
  const match = block[1].match(new RegExp(`${level}:\\s*"(#[0-9a-fA-F]{3,8})"`));
  if (!match) throw new Error(`no fallback colour for ${level}`);
  return match[1].toLowerCase();
}

describe("severity colours", () => {
  const levels = [
    ["NORMAL", "--color-status-normal"],
    ["MODERATE", "--color-status-moderate"],
    ["HIGH", "--color-status-high"],
    ["CRITICAL", "--color-status-critical"],
  ] as const;

  it.each(levels)("map's %s fallback matches the token", (level, token) => {
    expect(mapFallback(level)).toBe(tokenValue(token));
  });

  it("keeps the four levels distinct from one another", () => {
    // Not a contrast check — just that no two severities were ever set to the
    // same value, which would make two different warnings indistinguishable.
    const values = levels.map(([, token]) => tokenValue(token));
    expect(new Set(values).size).toBe(4);
  });
});

describe("theme-color", () => {
  it("matches the base surface in both themes", () => {
    // The browser paints its chrome with this before any CSS loads, so it is a
    // literal rather than a token — and therefore the one value that can fall
    // out of step with the palette without anything looking broken in the app.
    // It is now one entry per scheme: a single value left the phone's status
    // bar in the dark theme's near-black above a white page, which is the one
    // surface the toggle cannot reach because the OS paints it.
    const layout = readFileSync(join(ROOT, "app", "layout.tsx"), "utf8");
    const declared = [...layout.matchAll(/color:\s*"(#[0-9a-fA-F]{3,8})"/g)]
      .map((m) => m[1].toLowerCase());

    expect(declared).toContain(tokenValue("--color-surface-base"));
    expect(declared).toContain(lightTokenValue("--color-surface-base"));
  });
});

describe("the light theme", () => {
  it("redefines every colour token the dark theme declares", () => {
    // A token left out does not fail: it keeps its dark value and appears as
    // one dark element in a light page — a black card, an unreadable label —
    // which is the failure mode of every half-finished theme.
    const css = readFileSync(join(ROOT, "app", "globals.css"), "utf8");
    const [dark, light] = css.split('html[data-theme="light"] {');
    const declared = (source: string) =>
      new Set([...source.matchAll(/(--color-[a-z-]+):/g)].map((m) => m[1]));

    const missing = [...declared(dark)].filter((token) => !declared(light).has(token));
    expect(missing, `not overridden for light: ${missing.join(", ")}`).toEqual([]);
  });
});

describe("the disabled token", () => {
  it("is not used for text that has to be read", () => {
    // It measures 2.8 : 1 in the light theme and 2.6 : 1 in the dark one, which
    // is correct for the *absence* of a value — an em-dash, "never", "No
    // reading" — and wrong for a heading. It was carrying the sidebar's section
    // headings, the card labels ("What this means", "Recommended action") and
    // the demo-data disclosure, all of which a reader is meant to read, at
    // under 3 : 1 in both themes.
    //
    // The ramp cannot absorb the fix: content-tertiary is already 5.1 : 1, so
    // raising the disabled step to 4.5 would collapse the two into one. The
    // labels move up instead.
    // readdirSync with recursive, not globSync: the latter is only typed in
    // newer @types/node than `npm ci` installs, so it type-checked here and
    // failed the deployment's build.
    const sources = readdirSync(ROOT, { recursive: true, encoding: "utf8" })
      .filter((f) => f.endsWith(".tsx") && !f.endsWith(".test.tsx"));
    const offenders: string[] = [];

    for (const file of sources) {
      const text = readFileSync(join(ROOT, file), "utf8");
      for (const match of text.matchAll(/class[nN]ame=\{?["`][^"`]*["`]/g)) {
        const classes = match[0];
        if (classes.includes("text-content-disabled") && classes.includes("uppercase")) {
          offenders.push(`${file}: ${classes.slice(0, 60)}`);
        }
      }
    }

    expect(offenders, offenders.join("\n")).toEqual([]);
  });
});
