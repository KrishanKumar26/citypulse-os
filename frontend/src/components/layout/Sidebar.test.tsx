import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";

import { Sidebar } from "./Sidebar";

/**
 * The rail must describe the account looking at it.
 *
 * <p>A VIEWER holds five permissions and used to be shown all fifteen modules as
 * live links, every one of which answered "You do not have permission". These
 * tests hold the two halves of the correction apart, because only one of them is
 * obvious: a module the account cannot open must not be a link, and a module it
 * can open must still be one. Gating too eagerly is the quieter failure — it
 * removes working navigation and looks like nothing at all.
 */

let permissions: string[] = [];
let signedIn = true;

vi.mock("@/lib/auth/session", () => ({
  useSession: () => ({
    user: signedIn ? { id: "u1", email: "viewer@example.com", permissions } : null,
    can: (permission: string) => permissions.includes(permission),
  }),
}));

vi.mock("next/navigation", () => ({ usePathname: () => "/command-center" }));

const VIEWER = ["city:read", "zone:read", "telemetry:read", "forecast:read", "alert:read"];

function link(name: string) {
  return screen.queryByRole("link", { name: new RegExp(name, "i") });
}

beforeEach(() => {
  permissions = [...VIEWER];
  signedIn = true;
});

describe("Sidebar permission gating", () => {
  it("does not link to a module the account cannot open", () => {
    render(<Sidebar />);

    // analytics:read and anomaly:read are exactly what VIEWER lacks.
    expect(link("AI Insights")).toBeNull();
    expect(link("Anomaly Detection")).toBeNull();
  });

  it("still links to every module the account can open", () => {
    render(<Sidebar />);

    for (const label of ["Command Center", "Live Intelligence", "Forecast", "Alerts", "Impact"]) {
      expect(link(label)).not.toBeNull();
    }
  });

  it("names the missing permission rather than only greying the row", () => {
    render(<Sidebar />);

    // Someone who cannot open a page needs to know what to ask for. "Disabled"
    // on its own gives them nothing to act on.
    expect(screen.getByTitle(/analytics:read/)).toBeInTheDocument();
  });

  it("says how many are locked, so a lock does not read as a fault", () => {
    render(<Sidebar />);
    expect(screen.getByText(/need permission your account lacks/i)).toBeInTheDocument();
  });

  it("locks nothing while the session is still restoring", () => {
    // user === null covers both the signed-out shell and the moment before the
    // profile lands. Padlocking the whole rail for that instant would flash a
    // forbidden-looking product at an administrator holding every permission.
    signedIn = false;
    permissions = [];
    render(<Sidebar />);

    expect(link("AI Insights")).not.toBeNull();
    expect(screen.queryByText(/need permission your account lacks/i)).toBeNull();
  });

  it("leaves a module open to everyone open, even with no permissions at all", () => {
    permissions = [];
    render(<Sidebar />);

    // A null permission means any signed-in user may open the page. Locking
    // these would blame the account for a restriction that does not exist.
    expect(link("Settings")).not.toBeNull();
    expect(link("API Management")).not.toBeNull();
  });
});
