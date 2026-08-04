"use client";

import { useQuery } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState, useSyncExternalStore, type ReactNode } from "react";
import { Sidebar } from "@/components/layout/Sidebar";
import { Topbar } from "@/components/layout/Topbar";
import { ErrorState, Skeleton } from "@/components/ui";
import { geoApi, platformApi } from "@/lib/api/endpoints";
import type { City } from "@/lib/api/types";
import { useSession } from "@/lib/auth/session";
import { CityContext } from "@/lib/city-context";

const SELECTED_CITY_KEY = "citypulse.selectedCity";

/**
 * Reads the remembered city from localStorage.
 *
 * {@link useSyncExternalStore} rather than an effect: localStorage is an
 * external store, and this is the hook designed for reading one without a
 * render-then-correct cycle. The server snapshot is null, so SSR and the first
 * client render agree and hydration stays clean.
 */
function useRememberedCityId(): string | null {
  return useSyncExternalStore(
    // The value only changes through handleSelectCity, which re-renders anyway,
    // so no subscription is needed.
    () => () => {},
    () => window.localStorage.getItem(SELECTED_CITY_KEY),
    () => null,
  );
}

/**
 * Authenticated shell (PRD §8).
 *
 * The route guard here is a redirect for unauthenticated users, not a security
 * boundary: every request the shell makes is independently authorised by the
 * API. A user who bypassed this would see an empty shell and a series of 401s.
 */
export default function AppLayout({ children }: { children: ReactNode }) {
  const router = useRouter();
  const { user, isLoading: sessionLoading } = useSession();
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [selectedCityId, setSelectedCityId] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionLoading && !user) {
      router.replace("/login");
    }
  }, [sessionLoading, user, router]);

  const citiesQuery = useQuery({
    queryKey: ["cities"],
    queryFn: () => geoApi.listCities(true),
    enabled: Boolean(user),
  });

  const platformQuery = useQuery({
    queryKey: ["platform"],
    queryFn: platformApi.info,
    staleTime: Infinity,
  });

  const cities = useMemo(() => citiesQuery.data ?? [], [citiesQuery.data]);
  const rememberedCityId = useRememberedCityId();

  /*
   * Derived, not stored in an effect. The selection is a pure function of the
   * loaded cities, the user's explicit choice, and what was remembered from a
   * previous visit — so it needs no effect, no cascading render, and no window
   * where the city is briefly wrong.
   */
  const selectedCity =
    cities.find((city) => city.id === selectedCityId) ??
    cities.find((city) => city.id === rememberedCityId) ??
    cities[0] ??
    null;

  function handleSelectCity(city: City) {
    setSelectedCityId(city.id);
    window.localStorage.setItem(SELECTED_CITY_KEY, city.id);
  }

  if (sessionLoading || !user) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-surface-base">
        <div className="w-64 space-y-3">
          <Skeleton className="h-3 w-32" />
          <Skeleton className="h-9 w-full" />
          <span className="sr-only">Restoring your session</span>
        </div>
      </div>
    );
  }

  return (
    <CityContext.Provider value={{ city: selectedCity, cities }}>
      <div className="flex h-screen overflow-hidden bg-surface-base">
        <div className="hidden lg:block">
          <Sidebar />
        </div>

        {sidebarOpen && (
          <div className="fixed inset-0 z-50 lg:hidden">
            <div
              className="absolute inset-0 bg-black/60"
              onClick={() => setSidebarOpen(false)}
              aria-hidden="true"
            />
            <div className="absolute left-0 top-0 h-full">
              <Sidebar onNavigate={() => setSidebarOpen(false)} />
            </div>
          </div>
        )}

        <div className="flex min-w-0 flex-1 flex-col">
          <Topbar
            cities={cities}
            selectedCity={selectedCity}
            onSelectCity={handleSelectCity}
            platform={platformQuery.data}
            onOpenSidebar={() => setSidebarOpen(true)}
          />

          <main id="main-content" className="flex-1 overflow-y-auto">
            {citiesQuery.isError ? (
              <ErrorState
                title="Could not load cities"
                message={
                  citiesQuery.error instanceof Error
                    ? citiesQuery.error.message
                    : "The city registry is unavailable."
                }
                onRetry={() => void citiesQuery.refetch()}
              />
            ) : (
              children
            )}
          </main>
        </div>
      </div>
    </CityContext.Provider>
  );
}
