import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { ThemeToggle } from "./ThemeToggle";
import { THEME_STORAGE_KEY } from "@/lib/theme";

function mockSystem(prefersDark: boolean) {
  vi.stubGlobal("matchMedia", (query: string) => ({
    matches: query.includes("dark") ? prefersDark : !prefersDark,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    addListener: vi.fn(),
    removeListener: vi.fn(),
    dispatchEvent: vi.fn(),
    onchange: null,
  }));
}

beforeEach(() => {
  window.localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});
afterEach(() => vi.unstubAllGlobals());

/**
 * What the toggle guarantees.
 *
 * The failure worth guarding is the quiet one: the button appearing to work
 * while the attribute the stylesheet keys on never changes, or the choice never
 * reaching storage so the theme resets on the next load.
 */
describe("ThemeToggle", () => {
  it("follows the system before anyone has chosen", () => {
    mockSystem(false);
    render(<ThemeToggle />);
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("obeys a stored choice over the system", () => {
    mockSystem(true);
    window.localStorage.setItem(THEME_STORAGE_KEY, "light");
    render(<ThemeToggle />);
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("switches the attribute the stylesheet actually keys on", async () => {
    mockSystem(true);
    const user = userEvent.setup();
    render(<ThemeToggle />);
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");

    await user.click(screen.getByRole("button"));
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
  });

  it("writes the choice down, so a reload keeps it", async () => {
    mockSystem(true);
    const user = userEvent.setup();
    render(<ThemeToggle />);

    await user.click(screen.getByRole("button"));
    expect(window.localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");
  });

  it("is a switch, not a cycle: twice returns where it started", async () => {
    mockSystem(true);
    const user = userEvent.setup();
    render(<ThemeToggle />);

    await user.click(screen.getByRole("button"));
    await user.click(screen.getByRole("button"));
    expect(document.documentElement.getAttribute("data-theme")).toBe("dark");
  });

  it("names the action rather than leaving it to an icon", async () => {
    mockSystem(true);
    const user = userEvent.setup();
    render(<ThemeToggle />);
    // A screen reader cannot see a sun.
    expect(screen.getByRole("button", { name: /switch to light mode/i })).toBeInTheDocument();

    await user.click(screen.getByRole("button"));
    expect(screen.getByRole("button", { name: /switch to dark mode/i })).toBeInTheDocument();
  });

  it("still switches when storage refuses to keep the choice", async () => {
    mockSystem(true);
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const user = userEvent.setup();
    render(<ThemeToggle />);

    await user.click(screen.getByRole("button"));
    // The choice does not survive the reload; refusing to switch at all would
    // be worse than that.
    expect(document.documentElement.getAttribute("data-theme")).toBe("light");
    vi.restoreAllMocks();
  });
});
