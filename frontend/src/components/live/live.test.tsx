import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";

import { KpiRow } from "./KpiRow";
import { LiveStatusBar } from "./LiveStatusBar";
import type { CityKpis, CitySnapshot } from "@/lib/api/types";

/**
 * The live dashboard's honesty guarantees.
 *
 * These specs are mostly about what the UI must *not* say. A dashboard that
 * renders an unmeasured value as 0 reports a dead feed as a calm city, and a
 * "Live" badge over three-hour-old numbers hides a stalled pipeline behind a
 * green dot. Both are the kind of wrong that gets acted on.
 */

function kpis(overrides: Partial<CityKpis> = {}): CityKpis {
  return {
    averageCongestion: "0.6200",
    averageSpeedKph: "38.40",
    totalVehicleCount: 12_400,
    averageAqi: 142,
    temperatureC: "28.50",
    precipitationMmH: "0.00",
    weatherCondition: "CLEAR",
    activeIncidents: 2,
    activeEvents: 1,
    activeAlerts: 3,
    averageRiskScore: "44.90",
    overallRiskLevel: "MODERATE",
    zonesReporting: 6,
    zonesMonitored: 8,
    zonesDegraded: 2,
    ...overrides,
  };
}

function snapshot(overrides: Partial<CitySnapshot> = {}): CitySnapshot {
  return {
    cityId: "city-1",
    citySlug: "bengaluru",
    cityName: "Bengaluru",
    timezone: "Asia/Kolkata",
    asOf: "2026-08-04T10:00:00Z",
    dataAgeSeconds: 120,
    stale: false,
    kpis: kpis(),
    zones: [],
    demoData: true,
    ...overrides,
  };
}

describe("KpiRow", () => {
  it("renders measured values", () => {
    render(<KpiRow kpis={kpis()} loading={false} />);

    expect(screen.getByText("62")).toBeInTheDocument(); // congestion %
    expect(screen.getByText("38.4")).toBeInTheDocument();
    expect(screen.getByText("142")).toBeInTheDocument();
  });

  it("says 'not measured' rather than showing zero for an absent reading", () => {
    // The distinction the whole dashboard rests on: a dead traffic feed must not
    // render as "0 km/h", which reads as gridlock.
    render(
      <KpiRow
        kpis={kpis({ averageSpeedKph: null, averageAqi: null, averageCongestion: null })}
        loading={false}
      />,
    );

    expect(screen.getAllByText("Not measured").length).toBeGreaterThanOrEqual(3);
    expect(screen.queryByText("0.0")).not.toBeInTheDocument();
  });

  it("distinguishes a measured zero from an absent reading", () => {
    render(<KpiRow kpis={kpis({ activeIncidents: 0 })} loading={false} />);

    // Zero incidents is a real measurement and must show as 0, not "not measured".
    expect(screen.getByText("0")).toBeInTheDocument();
  });

  it("says what the city averages were computed over", () => {
    // A figure from 6 of 8 zones is a different claim from one over all 8.
    render(<KpiRow kpis={kpis()} loading={false} />);
    expect(screen.getByText("6 of 8 zones reporting")).toBeInTheDocument();
  });

  it("shows skeletons instead of values while loading", () => {
    render(<KpiRow kpis={null} loading />);
    expect(screen.queryByText("Not measured")).not.toBeInTheDocument();
  });
});

describe("LiveStatusBar", () => {
  it("reports a healthy stream", () => {
    render(
      <LiveStatusBar
        snapshot={snapshot()}
        status="live"
        lastEventAt={new Date("2026-08-04T10:02:00Z")}
        onReconnect={vi.fn()}
      />,
    );

    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.queryByText("Data is stale")).not.toBeInTheDocument();
  });

  it("flags stale data even while the stream is healthy", () => {
    // A connected stream delivering old windows is a pipeline problem. Reporting
    // only "Live" would hide it, and someone would refresh the page instead of
    // checking ingestion.
    render(
      <LiveStatusBar
        snapshot={snapshot({ stale: true, dataAgeSeconds: 10_800 })}
        status="live"
        lastEventAt={new Date()}
        onReconnect={vi.fn()}
      />,
    );

    expect(screen.getByText("Live")).toBeInTheDocument();
    expect(screen.getByText("Data is stale")).toBeInTheDocument();
  });

  it("distinguishes 'no telemetry yet' from 'stale telemetry'", () => {
    render(
      <LiveStatusBar
        snapshot={snapshot({ asOf: null, dataAgeSeconds: null, stale: true })}
        status="live"
        lastEventAt={new Date()}
        onReconnect={vi.fn()}
      />,
    );

    expect(screen.getByText("No telemetry received")).toBeInTheDocument();
  });

  it("offers a manual retry only when the stream is not healthy", () => {
    const { rerender } = render(
      <LiveStatusBar snapshot={snapshot()} status="live" lastEventAt={new Date()} onReconnect={vi.fn()} />,
    );
    expect(screen.queryByRole("button", { name: /reconnect/i })).not.toBeInTheDocument();

    rerender(
      <LiveStatusBar snapshot={snapshot()} status="offline" lastEventAt={null} onReconnect={vi.fn()} />,
    );
    expect(screen.getByRole("button", { name: /reconnect/i })).toBeInTheDocument();
  });

  it("labels synthetic data (PRD §42)", () => {
    render(
      <LiveStatusBar snapshot={snapshot()} status="live" lastEventAt={new Date()} onReconnect={vi.fn()} />,
    );
    expect(screen.getByText("DEMO DATA")).toBeInTheDocument();
  });
});
