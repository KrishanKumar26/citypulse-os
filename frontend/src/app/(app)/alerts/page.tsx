"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";

import {
  Badge,
  Button,
  Card,
  CardHeader,
  EmptyState,
  ErrorState,
  LoadingState,
  PageHeader,
} from "@/components/ui";
import { ApiRequestError } from "@/lib/api/client";
import { alertApi } from "@/lib/api/endpoints";
import type { AlertDetail, AlertSeverity, AlertStatus } from "@/lib/api/types";
import { useSelectedCity } from "@/lib/city-context";

/**
 * Alert Center (PRD §17).
 *
 * Deliberately provenance-first: every alert can be expanded to show the rule
 * that fired, the metric it read, the value it saw and the threshold it crossed.
 * An alert a user has to take on faith is one they eventually learn to ignore,
 * and PRD §15 requires the platform to cite the data behind what it claims.
 */

const SEVERITY_BADGE: Record<AlertSeverity, "normal" | "moderate" | "high" | "critical"> = {
  LOW: "normal",
  MEDIUM: "moderate",
  HIGH: "high",
  CRITICAL: "critical",
};

const STATUS_LABEL: Record<AlertStatus, string> = {
  NEW: "New",
  ACKNOWLEDGED: "Acknowledged",
  INVESTIGATING: "Investigating",
  RESOLVED: "Resolved",
};

export default function AlertsPage() {
  const { city } = useSelectedCity();
  const queryClient = useQueryClient();
  const [openOnly, setOpenOnly] = useState(true);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  const alertsQuery = useQuery({
    queryKey: ["alerts", city?.id, openOnly],
    queryFn: () => alertApi.list({ cityId: city!.id, openOnly, size: 100 }),
    enabled: Boolean(city),
    // Alerts are raised by a scheduled engine, not by anything the user does, so
    // the list goes stale on its own. Refetching keeps the Alert Center honest
    // without asking anyone to reload.
    refetchInterval: 30_000,
  });

  const transition = useMutation({
    mutationFn: ({ alert, status }: { alert: AlertDetail; status: AlertStatus }) =>
      alertApi.setStatus(alert.id, status),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["alerts"] }),
  });

  if (!city) {
    return <LoadingState label="Loading city" rows={4} />;
  }

  const alerts = alertsQuery.data?.items ?? null;
  const error = alertsQuery.isError
    ? alertsQuery.error instanceof Error
      ? alertsQuery.error.message
      : "Could not load alerts."
    : transition.isError
      ? transition.error instanceof ApiRequestError && transition.error.isForbidden
        ? "You do not have permission to work alerts."
        : transition.error instanceof Error
          ? transition.error.message
          : "Could not update the alert."
      : null;

  return (
    <div className="space-y-5 p-5">
      <PageHeader
        title="Alert Center"
        subtitle={`${city.name} · raised automatically when conditions cross a line`}
        actions={
          <>
            <Button
              variant={openOnly ? "primary" : "secondary"}
              size="sm"
              onClick={() => setOpenOnly(true)}
            >
              Open
            </Button>
            <Button
              variant={openOnly ? "secondary" : "primary"}
              size="sm"
              onClick={() => setOpenOnly(false)}
            >
              All
            </Button>
          </>
        }
      />

      {error && <ErrorState title="Alert Center" message={error} onRetry={() => void alertsQuery.refetch()} />}

      <Card className="overflow-hidden">
        <CardHeader
          title={openOnly ? "Open alerts" : "All alerts"}
          description="Most severe first. Expand an alert to see the measurement behind it."
        />

        {alertsQuery.isLoading ? (
          <LoadingState label="Loading alerts" rows={4} />
        ) : !alerts || alerts.length === 0 ? (
          <EmptyState
            title={openOnly ? "No open alerts" : "No alerts"}
            description={
              openOnly
                ? "Nothing currently crosses an alerting threshold in this city."
                : "No alert has been raised for this city yet."
            }
          />
        ) : (
          <ul className="divide-y divide-line-subtle">
            {alerts.map((alert) => (
              <li key={alert.id} className="px-5 py-3.5">
                <div className="flex flex-wrap items-start gap-3">
                  <Badge level={SEVERITY_BADGE[alert.severity]}>{alert.severity}</Badge>

                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-[13px] font-medium text-content-primary">
                        {alert.title}
                      </span>
                      <span className="text-[11px] text-content-tertiary">
                        {STATUS_LABEL[alert.status]}
                      </span>
                    </div>
                    <p className="mt-0.5 text-[13px] text-content-secondary">{alert.description}</p>

                    {alert.recommendedAction && (
                      <p className="mt-1.5 text-[12px] text-content-tertiary">
                        <span className="text-content-secondary">Recommended:</span>{" "}
                        {alert.recommendedAction}
                      </p>
                    )}

                    <button
                      type="button"
                      onClick={() => setExpandedId(expandedId === alert.id ? null : alert.id)}
                      className="mt-1.5 text-[12px] text-accent hover:underline"
                      aria-expanded={expandedId === alert.id}
                    >
                      {expandedId === alert.id ? "Hide evidence" : "Why did this fire?"}
                    </button>

                    {expandedId === alert.id && (
                      <dl className="mt-2 grid gap-x-6 gap-y-1 rounded-md border border-line-subtle bg-surface-overlay px-3 py-2 text-[12px] sm:grid-cols-2">
                        <Evidence label="Rule" value={alert.ruleCode} />
                        <Evidence label="Metric" value={alert.metricName} />
                        <Evidence label="Observed" value={alert.observedValue} />
                        <Evidence label="Threshold" value={alert.thresholdValue} />
                        <Evidence
                          label="From window"
                          value={alert.windowStart ? new Date(alert.windowStart).toLocaleString() : null}
                        />
                        <Evidence label="Zone" value={alert.zoneName} />
                        <Evidence label="Raised" value={new Date(alert.raisedAt).toLocaleString()} />
                        {alert.acknowledgedBy && (
                          <Evidence label="Acknowledged by" value={alert.acknowledgedBy} />
                        )}
                        {alert.resolvedBy && <Evidence label="Resolved by" value={alert.resolvedBy} />}
                      </dl>
                    )}
                  </div>

                  {/*
                    Only the transitions the alert's current state allows. A
                    resolved alert offers none: reopening is refused by the API,
                    and a button that always fails is worse than no button
                    (PRD §30 of the execution prompt).
                  */}
                  <div className="flex shrink-0 gap-2">
                    {alert.status === "NEW" && (
                      <Button
                        variant="secondary"
                        size="sm"
                        loading={transition.isPending && transition.variables?.alert.id === alert.id}
                        onClick={() => transition.mutate({ alert, status: "ACKNOWLEDGED" })}
                      >
                        Acknowledge
                      </Button>
                    )}
                    {(alert.status === "NEW" || alert.status === "ACKNOWLEDGED") && (
                      <Button
                        variant="ghost"
                        size="sm"
                        loading={transition.isPending && transition.variables?.alert.id === alert.id}
                        onClick={() => transition.mutate({ alert, status: "INVESTIGATING" })}
                      >
                        Investigate
                      </Button>
                    )}
                    {alert.status !== "RESOLVED" && (
                      <Button
                        variant="ghost"
                        size="sm"
                        loading={transition.isPending && transition.variables?.alert.id === alert.id}
                        onClick={() => transition.mutate({ alert, status: "RESOLVED" })}
                      >
                        Resolve
                      </Button>
                    )}
                  </div>
                </div>
              </li>
            ))}
          </ul>
        )}
      </Card>
    </div>
  );
}

function Evidence({ label, value }: { label: string; value: string | null }) {
  return (
    <div className="flex justify-between gap-3">
      <dt className="text-content-tertiary">{label}</dt>
      <dd className="tabular text-content-primary">{value ?? "—"}</dd>
    </div>
  );
}
