"use client";

import { Badge, Button } from "@/components/ui";
import type { LiveStatus } from "@/lib/live/useLiveSnapshot";
import type { CitySnapshot } from "@/lib/api/types";

/**
 * Connection and freshness banner.
 *
 * Two different things can be wrong and they need separating: the *stream* can
 * be down while the data is recent, and the stream can be perfectly healthy
 * while the *pipeline* has stopped writing. Collapsing them into one indicator
 * would make a stalled ingestion look like a network blip, and someone would
 * refresh the page instead of checking Spark.
 */

const STATUS_COPY: Record<LiveStatus, { label: string; level: "normal" | "moderate" | "critical" }> = {
  connecting: { label: "Connecting", level: "moderate" },
  live: { label: "Live", level: "normal" },
  reconnecting: { label: "Reconnecting", level: "moderate" },
  offline: { label: "Disconnected", level: "critical" },
};

function relativeAge(seconds: number): string {
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}

export function LiveStatusBar({
  snapshot,
  status,
  lastEventAt,
  onReconnect,
}: {
  snapshot: CitySnapshot | null;
  status: LiveStatus;
  lastEventAt: Date | null;
  onReconnect: () => void;
}) {
  const connection = STATUS_COPY[status];

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-2 rounded-lg border border-line-subtle bg-surface-raised px-4 py-2.5">
      <div className="flex items-center gap-2">
        <span
          className={
            status === "live"
              ? "h-1.5 w-1.5 rounded-full bg-status-normal pulse-dot"
              : status === "offline"
                ? "h-1.5 w-1.5 rounded-full bg-status-critical"
                : "h-1.5 w-1.5 rounded-full bg-status-moderate animate-pulse"
          }
          aria-hidden="true"
        />
        <span className="text-[13px] text-content-secondary">{connection.label}</span>
      </div>

      {lastEventAt && status === "live" && (
        <span className="text-[12px] text-content-tertiary">
          last update {lastEventAt.toLocaleTimeString()}
        </span>
      )}

      {/*
        Data age is reported separately from connection state. A healthy stream
        delivering three-hour-old windows is a pipeline problem, and saying only
        "Live" would hide it behind a green dot.
      */}
      {snapshot?.asOf && (
        <span className="text-[12px] text-content-tertiary">
          data as of {new Date(snapshot.asOf).toLocaleTimeString()}
          {snapshot.dataAgeSeconds !== null && ` (${relativeAge(snapshot.dataAgeSeconds)})`}
        </span>
      )}

      {snapshot?.stale && (
        <Badge level="high">
          {snapshot.asOf ? "Data is stale" : "No telemetry received"}
        </Badge>
      )}


      <div className="flex-1" />

      {(status === "offline" || status === "reconnecting") && (
        <Button variant="secondary" size="sm" onClick={onReconnect}>
          Reconnect now
        </Button>
      )}
    </div>
  );
}
