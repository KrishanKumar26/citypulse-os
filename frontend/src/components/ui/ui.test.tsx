import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { Badge, Button, DemoDataBadge, EmptyState, ErrorState, Input } from ".";
import { DATA_DISCLOSURE } from "@/lib/wording";

describe("Button", () => {
  it("calls its handler when clicked", async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Run simulation</Button>);

    await userEvent.click(screen.getByRole("button", { name: "Run simulation" }));

    expect(onClick).toHaveBeenCalledOnce();
  });

  it("is disabled while loading so a double submit cannot fire twice", async () => {
    const onClick = vi.fn();
    render(<Button loading onClick={onClick}>Save</Button>);

    const button = screen.getByRole("button");
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-busy", "true");

    await userEvent.click(button);
    expect(onClick).not.toHaveBeenCalled();
  });
});

describe("Input", () => {
  it("associates its label with the control", () => {
    render(<Input label="Email" />);
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
  });

  it("marks the field invalid and links the error message", () => {
    render(<Input label="Password" error="Password is too short" />);

    const input = screen.getByLabelText("Password");
    expect(input).toHaveAttribute("aria-invalid", "true");
    // A screen reader must be able to reach the explanation from the field.
    expect(input).toHaveAccessibleDescription("Password is too short");
    expect(screen.getByRole("alert")).toHaveTextContent("Password is too short");
  });

  it("shows the hint when there is no error, and hides it once there is", () => {
    const { rerender } = render(<Input label="Password" hint="At least 12 characters" />);
    expect(screen.getByText("At least 12 characters")).toBeInTheDocument();

    rerender(<Input label="Password" hint="At least 12 characters" error="Too short" />);
    expect(screen.queryByText("At least 12 characters")).not.toBeInTheDocument();
  });
});

describe("DemoDataBadge", () => {
  it("labels synthetic data, so it is never mistaken for live readings", () => {
    render(<DemoDataBadge />);
    expect(screen.getByText("PARTLY SYNTHETIC")).toBeInTheDocument();
  });

  it("does not claim the whole city is generated", () => {
    // It read DEMO DATA across every city, which was true until air quality
    // became real. A blanket label over a real reading is the same failure as
    // no label over a generated one, pointed the other way — and it buries the
    // platform's strongest claim under a word that reads as "none of this
    // counts".
    render(<DemoDataBadge />);
    expect(screen.queryByText(/DEMO DATA/)).not.toBeInTheDocument();
  });

  it("takes its hover text from DATA_DISCLOSURE rather than a copy of it", () => {
    // This badge held its own hand-written sentence and it went stale exactly
    // as the five earlier copies did: it went on saying traffic was generated
    // for as long as TomTom had been feeding it. Asserting the constant, not a
    // phrase from it, is what makes the next feed's arrival a one-line change.
    render(<DemoDataBadge />);
    expect(screen.getByTitle(DATA_DISCLOSURE)).toBeInTheDocument();
  });

  it("never describes a real feed as generated", () => {
    // The spec the stale copy failed. Whatever the sentence says, no feed with
    // a real source may appear on the generated side of it.
    const [real, generated = ""] = DATA_DISCLOSURE.split(/generated|the rest/i);
    for (const feed of [/\bair\b/i, /weather/i, /road speeds/i]) {
      expect(real).toMatch(feed);
      expect(generated).not.toMatch(feed);
    }
  });
});

describe("Badge", () => {
  it("renders its content", () => {
    render(<Badge level="critical">Critical</Badge>);
    expect(screen.getByText("Critical")).toBeInTheDocument();
  });
});

describe("state components", () => {
  it("EmptyState explains the absence rather than showing a blank panel", () => {
    render(<EmptyState title="No zones defined" description="This city has no active zones yet." />);

    expect(screen.getByRole("heading", { name: "No zones defined" })).toBeInTheDocument();
    expect(screen.getByText("This city has no active zones yet.")).toBeInTheDocument();
  });

  it("ErrorState announces itself and offers a retry", async () => {
    const onRetry = vi.fn();
    render(<ErrorState message="Zone data is unavailable." onRetry={onRetry} />);

    expect(screen.getByRole("alert")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Try again" }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it("ErrorState omits the retry control when no handler is given", () => {
    render(<ErrorState message="Zone data is unavailable." />);
    expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
  });
});

