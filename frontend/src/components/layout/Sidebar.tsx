"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/components/ui";

/**
 * Primary navigation (PRD §8).
 *
 * Modules that are not built yet are still listed, because hiding the roadmap
 * would misrepresent the product's shape. They are visibly marked and lead to a
 * page that states what is missing — never to a broken screen or a control that
 * silently does nothing (PRD §30 of the execution prompt).
 *
 * `available` must match what the page actually does. These flags were written
 * in Phase 2 and not revisited as Phases 4 to 7 shipped, so Live Intelligence,
 * Forecast, the Simulator, AI Insights and Alerts all sat behind a "Soon" badge
 * while working and serving real data. The rule cuts both ways: claiming a
 * feature that does not exist and hiding one that does are the same failure,
 * and the second is the one that survived to the first deployment.
 */

interface NavItem {
  label: string;
  href: string;
  icon: string;
  available: boolean;
}

const NAV_SECTIONS: { heading: string; items: NavItem[] }[] = [
  {
    heading: "Overview",
    items: [
      { label: "Command Center", href: "/command-center", icon: "grid", available: true },
      { label: "Live Intelligence", href: "/live", icon: "activity", available: true },
    ],
  },
  {
    heading: "Intelligence",
    items: [
      { label: "AI Insights", href: "/insights", icon: "sparkle", available: true },
      { label: "Anomaly Detection", href: "/anomalies", icon: "pulse", available: true },
      { label: "Forecast", href: "/forecast", icon: "trending", available: true },
      { label: "What-If Simulator", href: "/simulator", icon: "beaker", available: true },
      { label: "Digital Twin", href: "/digital-twin", icon: "layers", available: false },
    ],
  },
  {
    heading: "Operations",
    items: [
      { label: "Alerts", href: "/alerts", icon: "bell", available: true },
    ],
  },
  {
    heading: "Analytics",
    items: [
      { label: "City Analytics", href: "/analytics", icon: "chart", available: true },
    ],
  },
  {
    heading: "Data",
    items: [
      { label: "Data Sources", href: "/data-sources", icon: "database", available: true },
      { label: "Data Health", href: "/data-health", icon: "shield", available: true },
      { label: "API Management", href: "/api-keys", icon: "key", available: true },
    ],
  },
  {
    heading: "System",
    items: [
      { label: "Settings", href: "/settings", icon: "settings", available: true },
    ],
  },
];

// Derived from NAV_SECTIONS rather than written down. The footer used to read
// "Phase 2 · Frontend foundation", which was true when it was typed and wrong
// for every release after it — the same staleness that left five working
// modules badged "Soon". A count that cannot drift is worth more than a label
// someone has to remember to change.
const ALL_ITEMS = NAV_SECTIONS.flatMap((section) => section.items);
const TOTAL_COUNT = ALL_ITEMS.length;
const AVAILABLE_COUNT = ALL_ITEMS.filter((item) => item.available).length;

export function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();

  return (
    <nav aria-label="Main navigation" className="flex h-full w-60 shrink-0 flex-col border-r border-line-subtle bg-surface-raised">
      <div className="flex h-14 items-center gap-2.5 border-b border-line-subtle px-5">
        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <rect x="1.5" y="1.5" width="21" height="21" rx="5" stroke="var(--color-accent)" strokeWidth="1.5" />
          <path d="M5 14.5h3l2-5 2.5 8 2-6 1.5 3H19" stroke="var(--color-accent)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span className="text-[14px] font-semibold tracking-tight">CityPulse OS</span>
      </div>

      <div className="flex-1 overflow-y-auto px-3 py-4">
        {NAV_SECTIONS.map((section) => (
          <div key={section.heading} className="mb-5">
            <div className="px-2 pb-1.5 text-[11px] font-medium uppercase tracking-[0.1em] text-content-disabled">
              {section.heading}
            </div>
            <ul className="space-y-0.5">
              {section.items.map((item) => {
                const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                return (
                  <li key={item.href}>
                    <Link
                      href={item.href}
                      onClick={onNavigate}
                      aria-current={active ? "page" : undefined}
                      className={cn(
                        "relative flex items-center gap-2.5 rounded-md py-1.5 pl-3 pr-2 text-[13px] transition-colors",
                        active
                          ? "bg-accent-subtle font-medium text-accent"
                          : "text-content-secondary hover:bg-surface-hover hover:text-content-primary",
                      )}
                    >
                      {/* A solid rail beside the tinted ground. On a dark field a
                          tint alone is a weak signal, and finding your place in a
                          fifteen-item rail should not take a second look. */}
                      {active && (
                        <span
                          aria-hidden="true"
                          className="absolute inset-y-1 left-0 w-[2px] rounded-full bg-accent"
                        />
                      )}
                      <NavIcon name={item.icon} />
                      <span className="flex-1 truncate">{item.label}</span>
                      {!item.available && (
                        <span
                          className="rounded border border-line-default px-1 py-px text-[9px] font-medium uppercase tracking-wide text-content-disabled"
                          title="Not implemented yet"
                        >
                          Soon
                        </span>
                      )}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </div>

      <div className="border-t border-line-subtle px-5 py-3">
        <p className="text-[11px] text-content-disabled">
          {AVAILABLE_COUNT} of {TOTAL_COUNT} modules built · demo data
        </p>
      </div>
    </nav>
  );
}

function NavIcon({ name }: { name: string }) {
  const paths: Record<string, string> = {
    grid: "M3 3h7v7H3zM14 3h7v7h-7zM3 14h7v7H3zM14 14h7v7h-7z",
    activity: "M3 12h4l3-8 4 16 3-8h4",
    pulse: "M3 12h3l2-4 3 9 3-14 2 9h5",
    trending: "M3 17l6-6 4 4 8-8M17 7h4v4",
    beaker: "M9 3v6L4 19a2 2 0 002 2h12a2 2 0 002-2l-5-10V3M8 3h8",
    sparkle: "M12 3l2 5 5 2-5 2-2 5-2-5-5-2 5-2z",
    layers: "M12 3l9 5-9 5-9-5zM3 13l9 5 9-5",
    bell: "M18 8a6 6 0 10-12 0c0 7-3 8-3 8h18s-3-1-3-8M13.7 21a2 2 0 01-3.4 0",
    chart: "M3 3v18h18M8 16V10M13 16V6M18 16v-4",
    shield: "M12 3l8 3v6c0 5-3.4 8.4-8 9-4.6-.6-8-4-8-9V6zM9 12l2 2 4-4",
    database: "M12 3c4.4 0 8 1.3 8 3s-3.6 3-8 3-8-1.3-8-3 3.6-3 8-3zM4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6",
    key: "M15 7a4 4 0 11-4 4l-7 7v3h3l7-7",
    settings: "M12 15a3 3 0 100-6 3 3 0 000 6zM19.4 15a1.7 1.7 0 00.3 1.9l.1.1a2 2 0 11-2.8 2.8l-.1-.1a1.7 1.7 0 00-1.9-.3 1.7 1.7 0 00-1 1.5V21a2 2 0 11-4 0v-.1A1.7 1.7 0 008.9 19a1.7 1.7 0 00-1.9.3l-.1.1a2 2 0 11-2.8-2.8l.1-.1a1.7 1.7 0 00.3-1.9 1.7 1.7 0 00-1.5-1H3a2 2 0 110-4h.1A1.7 1.7 0 004.6 8.9a1.7 1.7 0 00-.3-1.9l-.1-.1a2 2 0 112.8-2.8l.1.1a1.7 1.7 0 001.9.3H9a1.7 1.7 0 001-1.5V3a2 2 0 114 0v.1a1.7 1.7 0 001 1.5 1.7 1.7 0 001.9-.3l.1-.1a2 2 0 112.8 2.8l-.1.1a1.7 1.7 0 00-.3 1.9V9a1.7 1.7 0 001.5 1H21a2 2 0 110 4h-.1a1.7 1.7 0 00-1.5 1z",
  };

  return (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      className="shrink-0"
    >
      <path d={paths[name] ?? paths.grid} />
    </svg>
  );
}
