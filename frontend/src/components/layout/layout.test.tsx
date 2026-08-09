import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { Topbar } from "./Topbar";
import type { City } from "@/lib/api/types";

// The real SessionProvider reaches for the app router, which is not mounted in
// a component test. What is under test here is the disclosure, not auth, so the
// session is stubbed rather than the router faked.
vi.mock("@/lib/auth/session", () => ({
  useSession: () => ({
    user: { id: "u1", email: "operator@example.com", fullName: "Operator", roles: [] },
    signOut: vi.fn(),
  }),
}));

/**
 * Guarantees that live in the shell rather than on any one page.
 *
 * The synthetic-data label used to be rendered three times on the Command
 * Center — top bar, page heading, and the freshness strip — and was asserted on
 * the freshness strip. Repetition does not make a disclosure stronger; it turns
 * it into decoration people stop reading. It now appears once, in the top bar,
 * which is on every page, so the test that protects PRD §42 belongs here. The
 * requirement did not move, only the place that satisfies it.
 */

function city(overrides: Partial<City> = {}): City {
  return {
    id: "c1",
    slug: "mumbai",
    name: "Mumbai",
    country: "India",
    countryCode: "IN",
    timezone: "Asia/Kolkata",
    centerLatitude: "19.0760",
    centerLongitude: "72.8777",
    defaultZoom: 11,
    population: 12_478_447,
    areaSqKm: "603.40",
    active: true,
    demoData: true,
    zoneCount: 6,
    ...overrides,
  } as City;
}

function renderTopbar(selected: City | null) {
  return render(<Topbar cities={[city()]} selectedCity={selected} onSelectCity={vi.fn()} />);
}

describe("Topbar", () => {
  it("labels synthetic data (PRD §42)", () => {
    renderTopbar(city());
    expect(screen.getByText("PARTLY SYNTHETIC")).toBeInTheDocument();
  });

  it("labels it at every width", () => {
    // It was previously hidden below the `sm` breakpoint. That was survivable
    // while two other badges existed on the page; with one, a phone would have
    // shown generated telemetry with nothing saying so.
    renderTopbar(city());
    expect(screen.getByText("PARTLY SYNTHETIC").className).not.toMatch(/\bhidden\b/);
  });

  it("does not label a city serving real data", () => {
    renderTopbar(city({ demoData: false }));
    expect(screen.queryByText("PARTLY SYNTHETIC")).not.toBeInTheDocument();
  });
});
