import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { TechnicalDetails } from "./TechnicalDetails";

const ROWS = [
  { label: "Current vehicles", value: "1047" },
  { label: "Historical baseline", value: "188.50" },
  { label: "Anomaly ratio", value: "5.6x" },
];

/**
 * The accordion's contract, which is mostly about what it must not do.
 *
 * A panel that opens by default defeats the point — the card exists so a reader
 * who does not work here is not met by engineering figures. A panel that hides
 * only visually leaves its rows in the tab order, so a keyboard user lands
 * inside a region they cannot see; that is the classic way an accordion built
 * with overflow alone fails.
 */
describe("TechnicalDetails", () => {
  it("is collapsed when the card first renders", () => {
    render(<TechnicalDetails rows={ROWS} />);
    expect(screen.getByRole("button", { name: /technical details/i }))
      .toHaveAttribute("aria-expanded", "false");
  });

  it("takes its rows out of the tab order while collapsed", () => {
    render(<TechnicalDetails rows={ROWS} />);
    // inert, not just hidden by overflow.
    expect(screen.getByRole("region", { hidden: true })).toHaveAttribute("inert");
  });

  it("opens and closes on click, and says which it is", async () => {
    const user = userEvent.setup();
    render(<TechnicalDetails rows={ROWS} />);
    const toggle = screen.getByRole("button", { name: /technical details/i });

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByRole("region")).not.toHaveAttribute("inert");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  it("opens from the keyboard", async () => {
    const user = userEvent.setup();
    render(<TechnicalDetails rows={ROWS} />);
    const toggle = screen.getByRole("button", { name: /technical details/i });

    toggle.focus();
    await user.keyboard("{Enter}");
    expect(toggle).toHaveAttribute("aria-expanded", "true");
  });

  it("renders every row it is given, and invents none", async () => {
    const user = userEvent.setup();
    render(<TechnicalDetails rows={ROWS} />);
    await user.click(screen.getByRole("button", { name: /technical details/i }));

    for (const row of ROWS) {
      expect(screen.getByText(row.label)).toBeInTheDocument();
      expect(screen.getByText(row.value)).toBeInTheDocument();
    }
    expect(screen.getByRole("region").querySelectorAll("dt")).toHaveLength(ROWS.length);
  });

  it("names the panel with the control that opens it", () => {
    // Without this a screen reader announces an unlabelled region.
    render(<TechnicalDetails rows={ROWS} />);
    const toggle = screen.getByRole("button", { name: /technical details/i });
    const region = screen.getByRole("region", { hidden: true });
    expect(toggle).toHaveAttribute("aria-controls", region.id);
    expect(region).toHaveAttribute("aria-labelledby", toggle.id);
  });
});
