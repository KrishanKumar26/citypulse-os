import { act, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

import { useLiveSnapshot } from "./useLiveSnapshot";

/**
 * Reconnection behaviour (Phase 4 exit criterion).
 *
 * This is worth testing precisely because the obvious implementation is wrong.
 * `EventSource` reconnects on its own — but it retries the *same URL*, and this
 * stream's URL carries a single-use ticket. Relying on the built-in retry would
 * mean every reconnect attempt 403s forever, so one dropped packet would kill
 * the dashboard permanently while looking like a network problem.
 *
 * So what is asserted here is that a dropped connection produces a *new ticket
 * and a new EventSource*, not a reuse of the old one.
 */

interface FakeSource {
  url: string;
  close: ReturnType<typeof vi.fn>;
  listeners: Map<string, (event: MessageEvent) => void>;
  onerror: (() => void) | null;
}

const created: FakeSource[] = [];
let ticketCounter = 0;

const streamTicket = vi.fn(async () => ({
  ticket: `ticket-${++ticketCounter}`,
  expiresInSeconds: 60,
}));

const snapshotFn = vi.fn(async () => ({
  cityId: "c1",
  citySlug: "bengaluru",
  cityName: "Bengaluru",
  timezone: "Asia/Kolkata",
  asOf: "2026-08-04T10:00:00Z",
  dataAgeSeconds: 60,
  stale: false,
  kpis: {} as never,
  zones: [],
  demoData: true,
}));

vi.mock("@/lib/api/endpoints", () => ({
  liveApi: {
    streamTicket: (...args: unknown[]) => streamTicket(...(args as [])),
    snapshot: (...args: unknown[]) => snapshotFn(...(args as [])),
  },
}));

class FakeEventSource {
  url: string;
  close = vi.fn();
  listeners = new Map<string, (event: MessageEvent) => void>();
  onerror: (() => void) | null = null;

  constructor(url: string) {
    this.url = url;
    created.push(this as unknown as FakeSource);
  }

  addEventListener(name: string, handler: (event: MessageEvent) => void) {
    this.listeners.set(name, handler);
  }

  /** Drives a server frame from the test. */
  emit(name: string, data: unknown) {
    this.listeners.get(name)?.({ data: JSON.stringify(data) } as MessageEvent);
  }
}

describe("useLiveSnapshot", () => {
  beforeEach(() => {
    created.length = 0;
    ticketCounter = 0;
    streamTicket.mockClear();
    snapshotFn.mockClear();
    vi.stubGlobal("EventSource", FakeEventSource);
    vi.useFakeTimers({ shouldAdvanceTime: true });
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("fetches a ticket and opens a stream carrying it", async () => {
    renderHook(() => useLiveSnapshot("bengaluru"));

    await waitFor(() => expect(created).toHaveLength(1));
    expect(created[0].url).toContain("ticket=ticket-1");
    expect(created[0].url).toContain("/api/v1/live/by-slug/bengaluru/stream");
  });

  it("goes live when a snapshot frame arrives", async () => {
    const { result } = renderHook(() => useLiveSnapshot("bengaluru"));
    await waitFor(() => expect(created).toHaveLength(1));

    act(() => {
      (created[0] as unknown as FakeEventSource).emit("snapshot", { cityName: "Bengaluru", zones: [] });
    });

    await waitFor(() => expect(result.current.status).toBe("live"));
    expect(result.current.snapshot).not.toBeNull();
  });

  it("requests a fresh ticket on reconnect rather than reusing the spent one", async () => {
    const { result } = renderHook(() => useLiveSnapshot("bengaluru"));
    await waitFor(() => expect(created).toHaveLength(1));

    act(() => {
      created[0].onerror?.();
    });

    await waitFor(() => expect(result.current.status).toBe("reconnecting"));

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });

    // A second EventSource with a *different* ticket. Reusing ticket-1 would be
    // rejected by the server, and the stream would never recover.
    await waitFor(() => expect(created.length).toBeGreaterThanOrEqual(2));
    expect(created[1].url).toContain("ticket=ticket-2");
    expect(created[1].url).not.toContain("ticket=ticket-1");
  });

  it("backs off rather than retrying tightly", async () => {
    renderHook(() => useLiveSnapshot("bengaluru"));
    await waitFor(() => expect(created).toHaveLength(1));

    act(() => created[0].onerror?.());
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    await waitFor(() => expect(created).toHaveLength(2));

    act(() => created[1].onerror?.());
    // The second gap is longer than the first, so a backend that is genuinely
    // down is not hammered by every open tab.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_200);
    });
    expect(created).toHaveLength(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(1_500);
    });
    await waitFor(() => expect(created).toHaveLength(3));
  });

  it("admits it is offline after repeated failures instead of claiming to reconnect forever", async () => {
    const { result } = renderHook(() => useLiveSnapshot("bengaluru"));
    await waitFor(() => expect(created).toHaveLength(1));

    for (let i = 0; i < 5; i++) {
      const source = created[created.length - 1];
      act(() => source.onerror?.());
      await act(async () => {
        await vi.advanceTimersByTimeAsync(35_000);
      });
    }

    await waitFor(() => expect(result.current.status).toBe("offline"));
  });

  it("closes the stream when the component unmounts", async () => {
    const { unmount } = renderHook(() => useLiveSnapshot("bengaluru"));
    await waitFor(() => expect(created).toHaveLength(1));

    unmount();

    // A leaked EventSource keeps a connection open and keeps writing into a
    // component that no longer exists.
    expect(created[0].close).toHaveBeenCalled();
  });

  it("opens a new stream when the selected city changes", async () => {
    const { rerender } = renderHook(({ slug }) => useLiveSnapshot(slug), {
      initialProps: { slug: "bengaluru" },
    });
    await waitFor(() => expect(created).toHaveLength(1));

    rerender({ slug: "mumbai" });

    await waitFor(() => expect(created.length).toBeGreaterThanOrEqual(2));
    expect(created[0].close).toHaveBeenCalled();
    expect(created[created.length - 1].url).toContain("/by-slug/mumbai/stream");
  });

  it("clears the previous city's data immediately on switch", async () => {
    const { result, rerender } = renderHook(({ slug }) => useLiveSnapshot(slug), {
      initialProps: { slug: "bengaluru" },
    });
    await waitFor(() => expect(created).toHaveLength(1));

    act(() => {
      (created[0] as unknown as FakeEventSource).emit("snapshot", { cityName: "Bengaluru", zones: [] });
    });
    await waitFor(() => expect(result.current.snapshot).not.toBeNull());

    rerender({ slug: "mumbai" });

    // Carrying the old snapshot across would render Bengaluru's congestion under
    // Mumbai's name for a frame.
    expect(result.current.snapshot).toBeNull();
  });
});
