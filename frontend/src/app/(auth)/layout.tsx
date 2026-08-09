import Link from "next/link";
import type { ReactNode } from "react";

import { BrandPanel } from "@/components/auth/BrandPanel";

/**
 * The shell every authentication screen sits in.
 *
 * Two columns from `lg` up: what the product is on the left, the form on the
 * right. Below that the panel is dropped rather than stacked — on a phone it
 * would push the form under a screen of prose, and someone opening a sign-in
 * link wants the fields, not the pitch. The compact header carries the mark so
 * the page is still identifiable.
 *
 * The form column keeps `max-w-sm` and centres itself, so all four screens —
 * sign in, sign up, forgot and reset — inherit this without changing a line of
 * their own. Sign-up is the tallest and scrolls inside its column rather than
 * moving the panel.
 */
export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-surface-base lg:grid lg:grid-cols-[minmax(0,1.05fr)_minmax(0,1fr)]">
      <aside className="hidden lg:block">
        <BrandPanel />
      </aside>

      {/* The form side sits one step up from the brand side. Two identical
          grounds separated by a hairline read as one page with a line drawn on
          it; a change of plane is what makes it read as two panels, and it puts
          the lighter one under the thing being filled in. */}
      <div className="flex min-h-screen flex-col bg-surface-raised">
        {/* Only below lg: the panel above carries the mark on wide screens. */}
        <header className="border-b border-line-subtle lg:hidden">
          <div className="flex h-14 items-center px-6">
            <Link href="/" className="flex items-center gap-2.5">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect x="1.5" y="1.5" width="21" height="21" rx="5" stroke="var(--color-accent)" strokeWidth="1.5" />
                <path
                  d="M5 14.5h3l2-5 2.5 8 2-6 1.5 3H19"
                  stroke="var(--color-accent)"
                  strokeWidth="1.5"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
              <span className="text-[15px] font-semibold tracking-tight">CityPulse OS</span>
            </Link>
          </div>
        </header>

        <main
          id="main-content"
          className="flex flex-1 items-center justify-center px-6 py-12 sm:px-10"
        >
          <div className="w-full max-w-sm">{children}</div>
        </main>

        <footer className="px-6 pb-6 lg:hidden">
          <p className="text-[11px] leading-relaxed text-content-disabled">
            Demonstration environment. Traffic, weather and incidents are synthetic
            and labelled as such; air quality is real.
          </p>
        </footer>
      </div>
    </div>
  );
}
