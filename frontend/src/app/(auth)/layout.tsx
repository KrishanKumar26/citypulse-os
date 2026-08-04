import Link from "next/link";
import type { ReactNode } from "react";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col bg-surface-base">
      <header className="border-b border-line-subtle">
        <div className="mx-auto flex h-14 max-w-6xl items-center px-6">
          <Link href="/" className="flex items-center gap-2.5">
            <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="1.5" y="1.5" width="21" height="21" rx="5" stroke="var(--color-accent)" strokeWidth="1.5" />
              <path d="M5 14.5h3l2-5 2.5 8 2-6 1.5 3H19" stroke="var(--color-accent)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
            </svg>
            <span className="text-[15px] font-semibold tracking-tight">CityPulse OS</span>
          </Link>
        </div>
      </header>

      <main id="main-content" className="flex flex-1 items-center justify-center px-6 py-12">
        <div className="w-full max-w-sm">{children}</div>
      </main>

      <footer className="border-t border-line-subtle">
        <div className="mx-auto max-w-6xl px-6 py-5">
          <p className="text-[12px] text-content-disabled">
            Demonstration environment. City telemetry is synthetic and labelled as demo data.
          </p>
        </div>
      </footer>
    </div>
  );
}
