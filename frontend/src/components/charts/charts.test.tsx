import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { RiskDistribution, ZoneRiskChart } from "./ZoneRiskChart";
import type { ZoneCondition } from "@/lib/api/types";

/**
 * What the chart must not say.
 *
 * The same honesty rules the KPI tiles follow apply to a plotted mark, and the
 * failure is quieter here: a zone with no telemetry drawn as a zero-length bar
 * does not look like missing data, it looks like the safest place in the city
 * and sorts to the bottom accordingly.
 */

function zone(overrides: Partial<ZoneCondition> = {}): ZoneCondition {
  return {
    zoneId: "z1",
    zoneCode: "MUM-BKC",
    zoneName: "Bandra Kurla",
    zoneType: "COMMERCIAL",
    latitude: "19.06",
    longitude: "72.86",
    windowStart: "2026-08-07T12:00:00Z",
    windowEnd: "2026-08-07T12:05:00Z",
    vehicleCount: 1800,
    averageSpeedKph: "12.40",
    occupancyRatio: "0.9100",
    congestionLevel: "HIGH",
    activeIncidents: 0,
    activeEvents: 0,
    riskScore: "71.20",
    riskLevel: "HIGH",
    sampleCount: 6,
    demoData: true,
    hasData: true,
    ...overrides,
  } as ZoneCondition;
}

describe("ZoneRiskChart", () => {
  it("ranks zones worst first", () => {
    render(
      <ZoneRiskChart
        zones={[
          zone({ zoneId: "a", zoneName: "Calm Road", riskScore: "22.00", riskLevel: "NORMAL" }),
          zone({ zoneId: "b", zoneName: "Bad Junction", riskScore: "88.00", riskLevel: "CRITICAL" }),
        ]}
      />,
    );

    const labels = screen.getAllByTitle(/Road|Junction/).map((el) => el.textContent);
    expect(labels).toEqual(["Bad Junction", "Calm Road"]);
  });

  it("excludes a zone that reported nothing, and says so", () => {
    // A silent feed has no risk score. Drawing it as zero would rank a dead
    // sensor as the safest zone in the city.
    render(
      <ZoneRiskChart
        zones={[
          zone({ zoneId: "a", zoneName: "Reporting" }),
          zone({ zoneId: "b", zoneName: "Silent", hasData: false, riskScore: null, riskLevel: null }),
        ]}
      />,
    );

    expect(screen.queryByTitle("Silent")).not.toBeInTheDocument();
    expect(screen.getByText(/1 zone reported nothing/)).toBeInTheDocument();
  });

  it("never leaves severity to colour alone", () => {
    // Four severity steps spanning green to red cannot be separated by hue
    // reliably — a written level accompanies every bar.
    render(<ZoneRiskChart zones={[zone({ riskLevel: "CRITICAL", riskScore: "91.00" })]} />);
    expect(screen.getByText("Critical")).toBeInTheDocument();
    expect(screen.getByText("91")).toBeInTheDocument();
  });

  it("says nothing rather than drawing an empty chart", () => {
    render(<ZoneRiskChart zones={[zone({ hasData: false, riskScore: null, riskLevel: null })]} />);
    expect(screen.getByText(/No zone reported a risk score/)).toBeInTheDocument();
  });
});

describe("RiskDistribution", () => {
  it("counts only reporting zones, and labels every segment", () => {
    const { container } = render(
      <RiskDistribution
        zones={[
          zone({ zoneId: "a", riskLevel: "NORMAL" }),
          zone({ zoneId: "b", riskLevel: "CRITICAL" }),
          zone({ zoneId: "c", riskLevel: "CRITICAL" }),
          zone({ zoneId: "d", hasData: false, riskLevel: null }),
        ]}
      />,
    );

    const legend = container.querySelectorAll("span.flex.items-center");
    expect(legend).toHaveLength(2);
    expect(within(legend[0] as HTMLElement).getByText("Normal")).toBeInTheDocument();
    expect(within(legend[1] as HTMLElement).getByText("Critical")).toBeInTheDocument();
    expect(within(legend[1] as HTMLElement).getByText("2")).toBeInTheDocument();
  });
});
