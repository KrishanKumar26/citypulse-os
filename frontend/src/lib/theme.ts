/**
 * Which theme is showing, and how it survives a reload.
 *
 * Three states, not two. A reader who has never chosen gets the theme their
 * operating system is set to; a reader who has chosen gets what they chose,
 * on every device where they chose it, until they choose again. Collapsing
 * "system" into a default of dark would mean someone whose machine is in light
 * mode is shown a dark product and has to fix it by hand every first visit.
 *
 * The stored value is the *choice*, so `system` is a real stored state and not
 * the absence of one. That distinction is what lets the page keep following the
 * OS after a reload rather than freezing whatever the OS happened to be on the
 * day the reader first arrived.
 */

export type ThemeChoice = "light" | "dark" | "system";
/** What is actually painted. `system` has been resolved by this point. */
export type ResolvedTheme = "light" | "dark";

export const THEME_STORAGE_KEY = "citypulse-theme";

export function isThemeChoice(value: unknown): value is ThemeChoice {
  return value === "light" || value === "dark" || value === "system";
}

/** The stored choice, or `system` when there is none or it is unreadable. */
export function readStoredChoice(storage: Pick<Storage, "getItem">): ThemeChoice {
  try {
    const stored = storage.getItem(THEME_STORAGE_KEY);
    return isThemeChoice(stored) ? stored : "system";
  } catch {
    // Storage can throw rather than return null — Safari in private mode, or a
    // browser configured to block site data. A theme is not worth an exception
    // reaching the page.
    return "system";
  }
}

export function resolveTheme(choice: ThemeChoice, systemPrefersDark: boolean): ResolvedTheme {
  if (choice === "system") return systemPrefersDark ? "dark" : "light";
  return choice;
}

/**
 * The script that runs before the first paint.
 *
 * Returned as a string and injected into the document head, ahead of any
 * stylesheet or markup, because the alternative is applying the theme in an
 * effect — which runs after React hydrates, which is after the browser has
 * already painted. A reader who chose light would watch the dark theme flash
 * past on every navigation. That flash is the whole reason this is inline and
 * blocking rather than a component.
 *
 * It writes an explicit `data-theme` even for the `system` choice, so the
 * stylesheet needs one selector rather than an attribute rule and a media query
 * that have to be kept saying the same thing.
 *
 * Deliberately small, dependency-free and wrapped in try/catch: it runs before
 * anything else on the page, so a throw here is a blank page rather than a
 * wrong colour.
 */
export const THEME_INIT_SCRIPT = `
(function () {
  try {
    var stored = localStorage.getItem(${JSON.stringify(THEME_STORAGE_KEY)});
    var choice = stored === "light" || stored === "dark" || stored === "system" ? stored : "system";
    var dark = choice === "dark" ||
      (choice === "system" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.setAttribute("data-theme", dark ? "dark" : "light");
  } catch (e) {
    document.documentElement.setAttribute("data-theme", "dark");
  }
})();
`.trim();
