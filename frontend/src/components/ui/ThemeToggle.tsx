"use client";

import { useEffect, useState } from "react";

import { cn } from "@/components/ui";
import {
  THEME_STORAGE_KEY,
  type ResolvedTheme,
  type ThemeChoice,
  readStoredChoice,
  resolveTheme,
} from "@/lib/theme";

/**
 * Switches the product between light and dark, and remembers the answer.
 *
 * One button rather than a three-way control. The stored model has three states
 * — light, dark and system — but a reader who wants to change the theme wants
 * the other one, and a segmented control asking them to think about "system"
 * costs more than it gives on a toolbar. Clicking always sets an explicit
 * choice: the button is a switch, not a cycle, so pressing it twice returns to
 * where it started rather than landing somewhere third.
 *
 * `system` still exists and is still the default. It is what a reader gets
 * before they have ever pressed this, and the OS is followed live while it
 * holds — a machine that switches to light at sunset switches the product with
 * it, on a page that was already open.
 *
 * The icon shows the theme that is *on*, not the one the button would go to.
 * Both conventions exist and both confuse someone; the label says which is
 * which, so the icon is left to describe the current state and the accessible
 * name carries the action.
 */
export function ThemeToggle({ className }: { className?: string }) {
  // Starts undefined, not "dark". The server has no way to know which theme
  // the document was opened in — the inline script decides that after the HTML
  // is sent — so rendering either icon here would mean rendering the wrong one
  // for half of readers and having React correct it on hydration.
  const [theme, setTheme] = useState<ResolvedTheme | undefined>(undefined);

  useEffect(() => {
    const media = window.matchMedia("(prefers-color-scheme: dark)");

    const apply = (choice: ThemeChoice) => {
      const resolved = resolveTheme(choice, media.matches);
      document.documentElement.setAttribute("data-theme", resolved);
      setTheme(resolved);
    };

    apply(readStoredChoice(window.localStorage));

    // Follow the operating system while no explicit choice is held. Re-reading
    // storage inside the handler rather than closing over the value keeps this
    // correct when another tab changes the choice.
    const onSystemChange = () => {
      if (readStoredChoice(window.localStorage) === "system") apply("system");
    };
    media.addEventListener("change", onSystemChange);

    // A choice made in one tab applies to the others. Without this, two open
    // tabs disagree until each is reloaded.
    const onStorage = (event: StorageEvent) => {
      if (event.key === THEME_STORAGE_KEY) apply(readStoredChoice(window.localStorage));
    };
    window.addEventListener("storage", onStorage);

    return () => {
      media.removeEventListener("change", onSystemChange);
      window.removeEventListener("storage", onStorage);
    };
  }, []);

  const toggle = () => {
    const next: ResolvedTheme = theme === "dark" ? "light" : "dark";
    try {
      window.localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      // Blocked storage means the choice does not survive the reload. It still
      // applies to this page, which is better than refusing to switch at all.
    }
    document.documentElement.setAttribute("data-theme", next);
    setTheme(next);
  };

  return (
    <button
      type="button"
      onClick={toggle}
      // The state is what the control is, so it is announced rather than left
      // to an icon a screen reader cannot see.
      aria-label={
        theme === undefined
          ? "Switch theme"
          : `Switch to ${theme === "dark" ? "light" : "dark"} mode`
      }
      title={theme === undefined ? undefined : `${theme === "dark" ? "Dark" : "Light"} mode`}
      className={cn(
        "inline-flex h-8 w-8 items-center justify-center rounded-md",
        "text-content-tertiary transition-colors",
        "hover:bg-surface-hover hover:text-content-primary",
        "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40",
        className,
      )}
    >
      {/* Nothing is drawn until the theme is known, so the first paint cannot
          show the wrong icon. The button keeps its size either way, so the
          toolbar does not shift when it fills in. */}
      {theme === "dark" ? <MoonIcon /> : theme === "light" ? <SunIcon /> : null}
    </button>
  );
}

function SunIcon() {
  return (
    <svg
      width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"
      aria-hidden="true" focusable="false"
    >
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

function MoonIcon() {
  return (
    <svg
      width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
      strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"
      aria-hidden="true" focusable="false"
    >
      <path d="M21 12.8A9 9 0 1111.2 3a7 7 0 009.8 9.8z" />
    </svg>
  );
}
