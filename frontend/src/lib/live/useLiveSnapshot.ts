"use client";

import { useCallback, useEffect, useRef, useState } from "react";

import { liveApi } from "@/lib/api/endpoints";
import type { CitySnapshot } from "@/lib/api/types";

/**
 * Subscribes to a city's live conditions over server-sent events.
 *
 * The connection dance is: exchange the session for a single-use ticket, open
 * an `EventSource` with it, then render whatever arrives. The ticket exists
 * because `EventSource` cannot send an `Authorization` header — see
 * `StreamTicketService` on the backend for why a token in the query string was
 * not an acceptable substitute.
 *
 * That ticket is also why this hook cannot lean on the browser's built-in
 * reconnection. `EventSource` retries the *same URL*, and the ticket in it is
 * spent — so every automatic retry would 403 forever and the stream would look
 * permanently dead after one dropped packet. Reconnection is therefore driven
 * here: fetch a fresh ticket, open a new stream, back off between attempts.
 *
 * The whole connection lifecycle lives inside the effect rather than in
 * `useCallback`s. Connecting and scheduling a retry are mutually recursive, and
 * splitting them across memoised callbacks creates a dependency cycle that has
 * to be broken with refs — more machinery than the thing it manages.
 */

export type LiveStatus = "connecting" | "live" | "reconnecting" | "offline";

interface LiveSnapshotState {
  snapshot: CitySnapshot | null;
  status: LiveStatus;
  /** When the last frame arrived, so the UI can show how current it is. */
  lastEventAt: Date | null;
  error: string | null;
  reconnect: () => void;
}

/**
 * Backoff schedule in milliseconds.
 *
 * Starts fast because most drops are momentary — a laptop waking, a proxy
 * recycling a connection — and a one-second gap there is invisible. Grows to
 * thirty seconds so a genuinely down backend is not hammered by every open tab,
 * which is how a recovering service gets knocked over again.
 */
const BACKOFF_MS = [1_000, 2_000, 5_000, 10_000, 30_000];

/** Attempts before the UI stops saying "reconnecting" and admits it is offline. */
const ATTEMPTS_BEFORE_OFFLINE = 3;

export function useLiveSnapshot(citySlug: string | null): LiveSnapshotState {
  const [snapshot, setSnapshot] = useState<CitySnapshot | null>(null);
  const [status, setStatus] = useState<LiveStatus>("connecting");
  const [lastEventAt, setLastEventAt] = useState<Date | null>(null);
  const [error, setError] = useState<string | null>(null);

  /** Bumped by {@link reconnect} to re-run the effect on demand. */
  const [retryNonce, setRetryNonce] = useState(0);

  // Reset during render rather than in an effect when the city changes.
  //
  // Doing it in the effect would render one frame of the previous city's data
  // under the new city's name — briefly showing Mumbai's congestion labelled
  // Bengaluru. Adjusting state during render is React's documented answer to
  // exactly this, and it avoids the extra commit an effect would cost.
  const [renderedSlug, setRenderedSlug] = useState(citySlug);
  if (citySlug !== renderedSlug) {
    setRenderedSlug(citySlug);
    setSnapshot(null);
    setStatus("connecting");
    setLastEventAt(null);
    setError(null);
  }

  const reconnect = useCallback(() => {
    setStatus("connecting");
    setError(null);
    setRetryNonce((n) => n + 1);
  }, []);

  const cancelledRef = useRef(false);

  useEffect(() => {
    if (!citySlug) return;

    cancelledRef.current = false;
    let source: EventSource | null = null;
    let timer: ReturnType<typeof setTimeout> | null = null;
    let attempt = 0;

    const scheduleReconnect = () => {
      if (cancelledRef.current) return;
      const delay = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
      attempt += 1;
      setStatus(attempt > ATTEMPTS_BEFORE_OFFLINE ? "offline" : "reconnecting");
      timer = setTimeout(open, delay);
    };

    async function open() {
      if (cancelledRef.current) return;

      try {
        const { ticket } = await liveApi.streamTicket(citySlug!);
        if (cancelledRef.current) return;

        const base = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8080";
        source = new EventSource(
          `${base}/api/v1/live/by-slug/${citySlug}/stream?ticket=${encodeURIComponent(ticket)}`,
        );

        source.addEventListener("snapshot", (event) => {
          if (cancelledRef.current) return;
          try {
            setSnapshot(JSON.parse((event as MessageEvent).data) as CitySnapshot);
            setLastEventAt(new Date());
            setStatus("live");
            setError(null);
            // Reset only on a frame that actually parsed. A server returning
            // malformed data is not a healthy connection, and clearing the
            // backoff there would retry it tightly.
            attempt = 0;
          } catch {
            setError("Received an unreadable update from the server.");
          }
        });

        // Heartbeats carry no conditions. They exist so a quiet city and a dead
        // socket are distinguishable, which matters because they look identical.
        source.addEventListener("heartbeat", () => {
          if (cancelledRef.current) return;
          setLastEventAt(new Date());
          setStatus("live");
        });

        source.onerror = () => {
          if (cancelledRef.current) return;
          // EventSource cannot tell "server closed" from "network dropped", and
          // its own retry would reuse the spent ticket, so the socket is closed
          // here and reconnection is driven explicitly.
          source?.close();
          source = null;
          scheduleReconnect();
        };
      } catch (cause) {
        if (cancelledRef.current) return;
        setError(cause instanceof Error ? cause.message : "Could not open the live stream.");
        scheduleReconnect();
      }
    }

    // Fetch once immediately as well as opening the stream. The stream's first
    // frame is quick but not instant, and an empty dashboard during the
    // handshake reads as a broken page rather than a loading one.
    void liveApi
      .snapshot(citySlug)
      .then((initial) => {
        if (cancelledRef.current) return;
        setSnapshot(initial);
        setLastEventAt(new Date());
      })
      .catch(() => {
        // Non-fatal: the stream is the real source, and its error handling
        // covers a backend that is genuinely unreachable.
      });

    void open();

    return () => {
      cancelledRef.current = true;
      source?.close();
      if (timer) clearTimeout(timer);
    };
  }, [citySlug, retryNonce]);

  return { snapshot, status, lastEventAt, error, reconnect };
}
