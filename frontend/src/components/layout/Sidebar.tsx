"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/components/ui";
import { useSession } from "@/lib/auth/session";
import { Glyph, type GlyphName } from "@/components/ui/icons";

/**
 * Primary navigation (PRD §8).
 *
 * Every item here is a module that exists and works. The rail carried an
 * unbuilt one behind a "Soon" badge until the roadmap it advertised stopped
 * being a plan — the Digital Twin needs a road network the platform has no
 * source for, so it was removed rather than left promising something. A rail
 * that lists what is not coming is no more honest than one that hides what is.
 *
 * That badge had already failed in the other direction: written in Phase 2 and
 * not revisited, it left Live Intelligence, Forecast, the Simulator, AI Insights
 * and Alerts marked "Soon" through Phases 4 to 7, while all five worked and
 * served real data. Claiming a feature that does not exist and hiding one that
 * does are the same failure, and the second is the one that survived to the
 * first deployment.
 *
 * `permission` applies the same rule to the signed-in account. VIEWER once held
 * five permissions and saw every module as a live link, most of which could only
 * answer "You do not have permission" — navigation that leads nowhere is
 * indistinguishable, to the person clicking it, from a product that is broken.
 *
 * VIEWER now reads every module (migration V17), so on this deployment the locks
 * below stay dark. They are kept because the rule, not the current grant, is what
 * matters: a deployment that narrows a role should grey the rail, not break it.
 *
 * Locked items stay visible rather than disappearing, so someone can discover a
 * capability exists in order to ask for access. The lock says the feature is
 * real and this account is not permitted; those are different facts from "not
 * built", which is why nothing here says both.
 *
 * Each permission is the one the page's own data actually requires, read off the
 * @PreAuthorize on the service behind it. This governs presentation only — the
 * API enforces the same permission independently.
 */

interface NavItem {
  label: string;
  href: string;
  icon: GlyphName;
  /** Null where any signed-in user may open the page. */
  permission: string | null;
}

const NAV_SECTIONS: { heading: string; items: NavItem[] }[] = [
  {
    heading: "Overview",
    items: [
      { label: "Command Center", href: "/command-center", icon: "grid", permission: "telemetry:read" },
      { label: "Live Intelligence", href: "/live", icon: "activity", permission: "telemetry:read" },
    ],
  },
  {
    heading: "Intelligence",
    items: [
      { label: "AI Insights", href: "/insights", icon: "sparkle", permission: "analytics:read" },
      { label: "Unusual Activity", href: "/anomalies", icon: "pulse", permission: "anomaly:read" },
      { label: "Forecast", href: "/forecast", icon: "trending", permission: "forecast:read" },
      { label: "What-If Simulator", href: "/simulator", icon: "beaker", permission: "simulation:read" },
    ],
  },
  {
    heading: "Operations",
    items: [
      { label: "Alerts", href: "/alerts", icon: "bell", permission: "alert:read" },
      { label: "Action Center", href: "/response-plans", icon: "check", permission: "alert:read" },
      { label: "Impact", href: "/impact", icon: "target", permission: "telemetry:read" },
    ],
  },
  {
    heading: "Analytics",
    items: [
      { label: "Trends", href: "/analytics", icon: "chart", permission: "telemetry:read" },
    ],
  },
  {
    heading: "Data",
    items: [
      { label: "Data Sources", href: "/data-sources", icon: "database", permission: "telemetry:read" },
      { label: "Data Health", href: "/data-health", icon: "shield", permission: "telemetry:read" },
      { label: "API Management", href: "/api-keys", icon: "key", permission: null },
    ],
  },
  {
    heading: "System",
    items: [
      { label: "Settings", href: "/settings", icon: "settings", permission: null },
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

export function Sidebar({ onNavigate }: { onNavigate?: () => void }) {
  const pathname = usePathname();
  const { can, user } = useSession();

  // Before the profile arrives nothing is locked. Rendering every item as
  // forbidden for the moment the session is restoring would flash a rail full
  // of padlocks at a user who holds all of them.
  const permitted = (item: NavItem) =>
    user === null || item.permission === null || can(item.permission);

  const lockedCount = user === null ? 0 : ALL_ITEMS.filter((i) => !permitted(i)).length;

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
            <div className="px-2 pb-1.5 text-[11px] font-medium uppercase tracking-[0.1em] text-content-tertiary">
              {section.heading}
            </div>
            <ul className="space-y-0.5">
              {section.items.map((item) => {
                const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                const locked = !permitted(item);

                if (locked) {
                  // Not a link. A disabled-looking link that still navigates is
                  // worse than either state on its own, and the destination
                  // would only render the API's refusal.
                  return (
                    <li key={item.href}>
                      <span
                        aria-disabled="true"
                        title={`Requires ${item.permission} — your account does not have it`}
                        className="flex cursor-not-allowed items-center gap-2.5 rounded-md py-1.5 pl-3 pr-2 text-[13px] text-content-disabled"
                      >
                        <Glyph name={item.icon} />
                        <span className="flex-1 truncate">{item.label}</span>
                        <LockIcon />
                      </span>
                    </li>
                  );
                }

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
                      <Glyph name={item.icon} />
                      <span className="flex-1 truncate">{item.label}</span>
                    </Link>
                  </li>
                );
              })}
            </ul>
          </div>
        ))}
      </div>

      <div className="border-t border-line-subtle px-5 py-3">
        <p className="text-[11px] text-content-tertiary">
          {TOTAL_COUNT} modules · partly synthetic
        </p>
        {lockedCount > 0 && (
          // Says why part of the rail is greyed. Without it a locked module is
          // indistinguishable from a broken one.
          <p className="mt-0.5 text-[11px] text-content-disabled">
            {lockedCount} need permission your account lacks
          </p>
        )}
      </div>
    </nav>
  );
}



function LockIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="4" y="10.5" width="16" height="10.5" rx="2" stroke="currentColor" strokeWidth="2" />
      <path d="M8 10.5V7a4 4 0 118 0v3.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}
