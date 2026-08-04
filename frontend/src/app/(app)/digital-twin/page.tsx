import { ComingSoon } from "@/components/ui";

/**
 * City Digital Twin is not implemented yet.
 *
 * The route exists so navigation never dead-ends, and states plainly what is
 * missing rather than presenting controls that do nothing (PRD §30 of the
 * execution prompt).
 */
export default function DigitalTwinPage() {
  return (
    <ComingSoon
      module="City Digital Twin"
      phase="Phase 4"
      capabilities={[
        "Per-zone traffic, crowd, air quality and risk state",
        "Zone detail with current conditions and forecast",
        "A 2D interactive representation, with the architecture left open to 3D later",
      ]}
    />
  );
}
