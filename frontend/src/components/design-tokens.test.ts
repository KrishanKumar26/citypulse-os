import { readFileSync } from "node:fs";
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
