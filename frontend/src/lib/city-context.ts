"use client";

import { createContext, useContext } from "react";
import type { City } from "@/lib/api/types";

interface CityContextValue {
  /** Null until the city list has loaded. */
  city: City | null;
  cities: City[];
}

/**
 * The currently selected city, chosen in the topbar and read by every module
 * beneath the shell. Held in context rather than the URL for now; when
 * per-city deep links are needed (Phase 4), the selection moves into the route
 * and this context reads from it.
 */
export const CityContext = createContext<CityContextValue>({ city: null, cities: [] });

export function useSelectedCity(): CityContextValue {
  return useContext(CityContext);
}
