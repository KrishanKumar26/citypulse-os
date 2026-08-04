"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { ApiRequestError } from "@/lib/api/client";
import { SessionProvider } from "@/lib/auth/session";

export function Providers({ children }: { children: ReactNode }) {
  // Created in state, not at module scope: a module-level client would be shared
  // across requests during SSR and leak one user's cached data into another's.
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // City and zone definitions change rarely. Live telemetry arrives in
            // Phase 4 over SSE and will set its own, much shorter, staleness.
            staleTime: 60_000,
            retry: (failureCount, error) => {
              // Retrying a 4xx just repeats a request the server already refused.
              if (error instanceof ApiRequestError && error.status < 500) return false;
              return failureCount < 2;
            },
            refetchOnWindowFocus: false,
          },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      <SessionProvider>{children}</SessionProvider>
    </QueryClientProvider>
  );
}
