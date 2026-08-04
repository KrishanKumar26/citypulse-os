import { ComingSoon } from "@/components/ui";

/**
 * Analytics is not implemented yet.
 *
 * The route exists so navigation never dead-ends, and states plainly what is
 * missing rather than presenting controls that do nothing (PRD §30 of the
 * execution prompt).
 */
export default function AnalyticsPage() {
  return (
    <ComingSoon
      module="Analytics"
      phase="Phase 5"
      capabilities={[
        "Historical trends filtered by city, zone, road, date, time and metric",
        "Traffic trends, congestion heatmaps, air quality and crowd trends",
        "Incident trends and weather correlation",
        "Prediction accuracy reporting and dataset export",
      ]}
    />
  );
}
