import { ComingSoon } from "@/components/ui";

/**
 * API Management is not implemented yet.
 *
 * The route exists so navigation never dead-ends, and states plainly what is
 * missing rather than presenting controls that do nothing (PRD §30 of the
 * execution prompt).
 */
export default function ApiKeysPage() {
  return (
    <ComingSoon
      module="API Management"
      phase="Phase 9"
      capabilities={[
        "Create and revoke API keys, with the secret shown once at creation",
        "Per-key usage, error rates and request counts",
        "Rate limit configuration and current consumption",
        "OpenAPI documentation for every published endpoint",
      ]}
    />
  );
}
