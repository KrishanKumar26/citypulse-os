import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { RiskDistribution, ZoneRiskChart } from "./ZoneRiskChart";
import { colorForLayer, CONDITION_COLORS } from "@/components/map/ZoneMap";
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


/**
 * The map's layers.
 *
 * The traffic layer is here because its first version restated the pipeline's
 * congestion thresholds from memory and got them wrong — 0.6/0.85 against the
 * real 0.55/0.80 — which would have painted a band of zones one level calmer
 * than the table beside them. It now reads the classification the pipeline
 * computed, and this asserts it does not go back to guessing.
 */
describe("map layers", () => {
  it("takes congestion from the pipeline's classification, not the raw ratio", () => {
    // A ratio that the old thresholds and the real ones disagree about.
    const c = zone({ occupancyRatio: "0.5800", congestionLevel: "MODERATE" });
    expect(colorForLayer("traffic", c)).toBe(CONDITION_COLORS.MODERATE);
  });

  it("greys a zone that reported traffic but no air quality", () => {
    // Per-layer absence, not whole-zone absence: the same zone stays coloured
    // on the layers it did report.
    const c = zone({ aqi: null, riskLevel: "HIGH" });
    expect(colorForLayer("air", c)).toBeNull();
    expect(colorForLayer("risk", c)).toBe(CONDITION_COLORS.HIGH);
  });

  it("treats zero incidents as a measurement, not an absence", () => {
    expect(colorForLayer("incidents", zone({ activeIncidents: 0 }))).toBe(CONDITION_COLORS.NORMAL);
    expect(colorForLayer("incidents", zone({ activeIncidents: 4 }))).toBe(CONDITION_COLORS.CRITICAL);
  });
});
