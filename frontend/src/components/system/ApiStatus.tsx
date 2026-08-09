"use client";

import { useEffect, useState } from "react";

import { API_BASE_URL } from "@/lib/api/client";
import { cn } from "@/components/ui";

/**
 * Whether the API is answering, asked before anyone signs in.
 *
 * It says what a browser can actually verify and nothing more. Every
 * `/api/v1` route requires a token, so this deliberately expects to be
 * refused: an HTTP 401 is a complete answer from a running service, and
 * receiving *any* status proves the API is reachable, has TLS, and is serving.
 * A promise that rejects means no response came back at all.
 *
 * `/actuator/health` would seem the obvious target and is the wrong one here.
 * It answers 200 to curl but carries no `access-control-allow-origin`, so a
 * browser cannot read it — the fetch would reject on a service that is
 * perfectly healthy, and the page would report an outage that is really a CORS
 * header. `/api/v1/cities` is inside the app's own CORS allowance, which is why
 * its refusal is legible from here.
 *
 * The pending state is its own state and not an optimistic green. This backend
 * sleeps on the free tier and takes about a minute to wake, so "checking" is
 * where an honest indicator spends most of its first visit, and saying so is
 * more useful to a visitor than a dot that is always green.
 */
type Reachability = "checking" | "responding" | "silent";

const LABEL: Record<Reachability, string> = {
  checking: "Contacting API",
  responding: "API responding",
  silent: "API not responding",
};

const DOT: Record<Reachability, string> = {
  checking: "bg-content-tertiary",
  responding: "bg-status-normal",
  silent: "bg-status-high",
};

/** Long enough for a cold container, short enough to not hang the indicator. */
const TIMEOUT_MS = 75_000;

export function ApiStatus({
  className,
  coldStartHint = false,
}: {
  className?: string;
  /**
   * Explain the wait while checking. True on the sign-in screen, where a
   * visitor is meeting a sleeping free-tier backend and has no way to know
   * that; false inside the application, where they are already signed in and
   * the container is by definition awake.
   */
  coldStartHint?: boolean;
}) {
  const [state, setState] = useState<Reachability>("checking");

  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);

    fetch(`${API_BASE_URL}/api/v1/cities`, {
      method: "GET",
      signal: controller.signal,
      // No credentials and no token: the 401 is the point.
      cache: "no-store",
    })
      .then(() => setState("responding"))
      // AbortError included: a request that never came back inside the timeout
      // is, from here, a service that is not responding.
      .catch(() => setState("silent"))
      .finally(() => clearTimeout(timer));

    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, []);

  return (
    <div className={cn("flex items-center gap-2", className)}>
      <span
        aria-hidden="true"
        className={cn(
          "h-1.5 w-1.5 shrink-0 rounded-full",
          DOT[state],
          state !== "silent" && "pulse-dot",
        )}
      />
      <span className="text-[12px] text-content-tertiary">{LABEL[state]}</span>
      {coldStartHint && state === "checking" && (
        <span className="text-[12px] text-content-disabled">· free tier, wakes in ~1 min</span>
      )}
    </div>
  );
}
