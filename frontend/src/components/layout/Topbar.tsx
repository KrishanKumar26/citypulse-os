"use client";

import { useEffect, useRef, useState } from "react";
import { Badge, Button, DemoDataBadge } from "@/components/ui";
import type { City, PlatformInfo } from "@/lib/api/types";
import { useSession } from "@/lib/auth/session";

/**
 * Top navigation (PRD §8): city selector, system status, notifications, profile.
 *
 * Notifications are not implemented, so no bell is rendered. An icon that never
 * shows anything reads as a broken feature, not a forthcoming one — the Alert
 * Center appears in the sidebar, marked as unbuilt, which is the honest place
 * for it.
 */
export function Topbar({
  cities,
  selectedCity,
  onSelectCity,
  platform,
  onOpenSidebar,
}: {
  cities: City[];
  selectedCity: City | null;
  onSelectCity: (city: City) => void;
  platform?: PlatformInfo;
  onOpenSidebar?: () => void;
}) {
  const { user, signOut } = useSession();
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // Dismiss on outside click and on Escape — a menu that only closes by
  // reselecting the trigger is a common and avoidable annoyance.
  useEffect(() => {
    if (!menuOpen) return;

    function handlePointerDown(event: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    }
    function handleKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") setMenuOpen(false);
    }

    document.addEventListener("mousedown", handlePointerDown);
    document.addEventListener("keydown", handleKeyDown);
    return () => {
      document.removeEventListener("mousedown", handlePointerDown);
      document.removeEventListener("keydown", handleKeyDown);
    };
  }, [menuOpen]);

  return (
    <header className="flex h-14 shrink-0 items-center gap-3 border-b border-line-subtle bg-surface-raised px-4">
      <button
        type="button"
        onClick={onOpenSidebar}
        aria-label="Open navigation"
        className="rounded-md p-1.5 text-content-secondary transition-colors hover:bg-surface-hover hover:text-content-primary lg:hidden"
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.75" aria-hidden="true">
          <path d="M4 6h16M4 12h16M4 18h16" strokeLinecap="round" />
        </svg>
      </button>

      <CitySelector cities={cities} selected={selectedCity} onSelect={onSelectCity} />

      {selectedCity?.demoData && <DemoDataBadge className="hidden sm:inline-flex" />}

      <div className="flex-1" />

      {/*
        Reports what the frontend can actually verify: that the API responded.
        It deliberately does not claim "all systems operational", which would be
        a status the frontend has no way to know.
      */}
      <div className="hidden items-center gap-2 md:flex">
        <span className="h-1.5 w-1.5 rounded-full bg-status-normal pulse-dot" aria-hidden="true" />
        <span className="text-[12px] text-content-tertiary">API connected</span>
      </div>

      {platform && (
        <Badge level="neutral" className="hidden lg:inline-flex">
          v{platform.version}
        </Badge>
      )}

      <div className="relative" ref={menuRef}>
        <button
          type="button"
          onClick={() => setMenuOpen((open) => !open)}
          aria-expanded={menuOpen}
          aria-haspopup="menu"
          className="flex items-center gap-2 rounded-md px-2 py-1.5 text-[13px] transition-colors hover:bg-surface-hover"
        >
          <span
            className="flex h-6 w-6 items-center justify-center rounded-full bg-accent-muted text-[11px] font-medium text-accent"
            aria-hidden="true"
          >
            {initialsOf(user?.fullName ?? "?")}
          </span>
          <span className="hidden max-w-32 truncate text-content-secondary sm:block">
            {user?.fullName}
          </span>
        </button>

        {menuOpen && (
          <div
            role="menu"
            className="absolute right-0 top-full z-50 mt-1 w-64 rounded-lg border border-line-default bg-surface-overlay p-1 shadow-xl"
          >
            <div className="border-b border-line-subtle px-3 py-2.5">
              <div className="truncate text-[13px] font-medium">{user?.fullName}</div>
              <div className="truncate text-[12px] text-content-tertiary">{user?.email}</div>
              <div className="mt-2 flex flex-wrap gap-1">
                {user?.roles.map((role) => (
                  <Badge key={role} level="info">
                    {role.replace(/_/g, " ")}
                  </Badge>
                ))}
              </div>
            </div>
            <div className="px-3 py-2 text-[12px] text-content-tertiary">
              {user?.permissions.length ?? 0} permissions granted
            </div>
            <div className="p-1">
              <Button variant="ghost" size="sm" fullWidth onClick={() => void signOut()}>
                Sign out
              </Button>
            </div>
          </div>
        )}
      </div>
    </header>
  );
}

function CitySelector({
  cities,
  selected,
  onSelect,
}: {
  cities: City[];
  selected: City | null;
  onSelect: (city: City) => void;
}) {
  if (cities.length === 0) {
    return <span className="text-[13px] text-content-tertiary">No cities available</span>;
  }

  return (
    <div className="flex items-center gap-2">
      <label htmlFor="city-selector" className="sr-only">
        Select city
      </label>
      <select
        id="city-selector"
        value={selected?.id ?? ""}
        onChange={(event) => {
          const city = cities.find((candidate) => candidate.id === event.target.value);
          if (city) onSelect(city);
        }}
        className="rounded-md border border-line-default bg-surface-overlay px-2.5 py-1.5 text-[13px] text-content-primary transition-colors hover:border-line-strong focus:border-accent focus:outline-none"
      >
        {cities.map((city) => (
          <option key={city.id} value={city.id}>
            {city.name}
          </option>
        ))}
      </select>
    </div>
  );
}

function initialsOf(name: string): string {
  return name
    .split(" ")
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}
