import { describe, expect, it } from "vitest";

import {
  THEME_INIT_SCRIPT,
  THEME_STORAGE_KEY,
  isThemeChoice,
  readStoredChoice,
  resolveTheme,
} from "./theme";

const storage = (value: string | null) => ({ getItem: () => value });

/**
 * The theme's three states and the one that must never be seen.
 *
 * "System" is a real stored choice rather than the absence of one. Treating it
 * as absence is how a product freezes whichever theme the machine happened to
 * be in the first time someone visited, and stops following it afterwards.
 */
describe("stored choice", () => {
  it("keeps a real choice", () => {
    expect(readStoredChoice(storage("light"))).toBe("light");
    expect(readStoredChoice(storage("dark"))).toBe("dark");
    expect(readStoredChoice(storage("system"))).toBe("system");
  });

  it("falls back to following the system, not to dark", () => {
    // Defaulting to "dark" would show a dark product to someone whose machine
    // is in light mode, every first visit, until they fixed it by hand.
    expect(readStoredChoice(storage(null))).toBe("system");
    expect(readStoredChoice(storage("chartreuse"))).toBe("system");
  });

  it("survives storage that throws instead of returning null", () => {
    // Safari in private mode, and any browser set to block site data. A theme
    // is not worth an exception reaching the page.
    const hostile = { getItem: () => { throw new Error("blocked"); } };
    expect(readStoredChoice(hostile)).toBe("system");
  });

  it("rejects anything that is not one of the three", () => {
    for (const value of ["", "Light", "DARK", null, undefined, 1, {}]) {
      expect(isThemeChoice(value), String(value)).toBe(false);
    }
  });
});

describe("resolveTheme", () => {
  it("obeys an explicit choice whatever the system says", () => {
    expect(resolveTheme("light", true)).toBe("light");
    expect(resolveTheme("dark", false)).toBe("dark");
  });

  it("follows the system only when no choice is held", () => {
    expect(resolveTheme("system", true)).toBe("dark");
    expect(resolveTheme("system", false)).toBe("light");
  });
});

describe("the pre-paint script", () => {
  it("sets the attribute the stylesheet keys on", () => {
    // The light palette is one selector, html[data-theme="light"]. If this
    // script ever stopped writing that attribute the whole theme would silently
    // stop applying, and every page would render dark with no error anywhere.
    expect(THEME_INIT_SCRIPT).toContain('setAttribute("data-theme"');
    expect(THEME_INIT_SCRIPT).toContain(JSON.stringify(THEME_STORAGE_KEY));
  });

  it("resolves the system preference itself", () => {
    // It writes an explicit light or dark even for the "system" choice, so the
    // stylesheet needs one selector rather than an attribute rule and a media
    // query that have to be kept saying the same thing.
    expect(THEME_INIT_SCRIPT).toContain("prefers-color-scheme: dark");
  });

  it("cannot throw the page away", () => {
    // It runs before anything else on the page. An uncaught throw here is a
    // blank document, not a wrong colour.
    expect(THEME_INIT_SCRIPT).toContain("catch");
  });

  it("is an expression the browser can run as written", () => {
    expect(() => new Function(THEME_INIT_SCRIPT)).not.toThrow();
  });
});
