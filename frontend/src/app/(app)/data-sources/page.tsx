import { ComingSoon } from "@/components/ui";

/**
 * Data Sources is not implemented yet.
 *
 * The route exists so navigation never dead-ends, and states plainly what is
 * missing rather than presenting controls that do nothing (PRD §30 of the
 * execution prompt).
 */
export default function DataSourcesPage() {
  return (
    <ComingSoon
      module="Data Sources"
      phase="Phase 3"
      capabilities={[
        "Registered ingestion sources and their health",
        "Kafka topic throughput and consumer lag",
        "Data quality metrics: missing values, duplicates and range violations",
        "Dead letter queue inspection with reason codes",
      ]}
    />
  );
}
