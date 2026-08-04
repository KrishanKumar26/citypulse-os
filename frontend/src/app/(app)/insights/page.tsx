import { ComingSoon } from "@/components/ui";

/**
 * AI Insights is not implemented yet.
 *
 * The route exists so navigation never dead-ends, and states plainly what is
 * missing rather than presenting controls that do nothing (PRD §30 of the
 * execution prompt).
 */
export default function InsightsPage() {
  return (
    <ComingSoon
      module="AI Insights"
      phase="Phase 7"
      capabilities={[
        "Explanations of what is happening and why, grounded in stored data",
        "Ranked contributing causes with the evidence behind each one",
        "Recommended actions selected by cause and severity",
        "An explicit 'insufficient data' response when the evidence is not there",
      ]}
    />
  );
}
