"use client";

import { useId, useState } from "react";

import { cn } from "@/components/ui";
import { Glyph } from "@/components/ui/icons";

/**
 * The engineering figures behind a card, folded away until asked for.
 *
 * The card above it is for someone deciding whether to take another route. The
 * rows in here are for someone deciding whether to trust the detection — the
 * baseline, the sample count, the spread it was judged against. Both readers
 * are real and they want opposite things from the same card, so the second set
 * is one click down rather than removed.
 *
 * **Collapsed is the default and stays the default.** No memory of the last
 * state, no "expand all": a citizen who opened one card yesterday should not
 * find every card unfolded today.
 *
 * The animation is a grid row going from 0fr to 1fr. A max-height transition
 * would need a guessed height, and any guess is either a clipped panel or a
 * pause at the end while the transition runs past the real content. This has no
 * magic number in it and works at any length.
 *
 * When closed, the panel is `inert` as well as visually collapsed. Without it
 * the rows stay in the tab order and a keyboard user tabs into a region they
 * cannot see — the classic failure of an accordion built only with overflow.
 */

export interface TechnicalRow {
  label: string;
  /** Rendered as given. Formatting belongs to the caller, which knows the units. */
  value: string;
}

export function TechnicalDetails({ rows }: { rows: TechnicalRow[] }) {
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const buttonId = useId();

  return (
    <div className="mt-3 border-t border-line-subtle pt-2.5">
      <button
        type="button"
        id={buttonId}
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((wasOpen) => !wasOpen)}
        className={cn(
          "group flex w-full items-center gap-1.5 rounded-md px-1 py-1 text-left",
          "text-[11px] font-medium text-content-tertiary transition-colors",
          "hover:text-content-secondary",
          "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent/40",
        )}
      >
        <Glyph name="settings" size={12} />
        <span>Technical Details</span>
        <Glyph
          name="chevron"
          size={12}
          className={cn(
            "ml-0.5 transition-transform duration-200 motion-reduce:transition-none",
            open && "rotate-180",
          )}
        />
      </button>

      {/* The 0fr/1fr row is the animation; the inner div owns the overflow so
          the panel's own padding is not clipped mid-transition. */}
      <div
        className={cn(
          "grid transition-[grid-template-rows] duration-200 ease-out motion-reduce:transition-none",
          open ? "grid-rows-[1fr]" : "grid-rows-[0fr]",
        )}
      >
        <div className="overflow-hidden">
          <div
            id={panelId}
            role="region"
            aria-labelledby={buttonId}
            inert={!open}
            className={cn(
              "mt-2 rounded-md border border-line-subtle bg-surface-base/60 p-3",
              "transition-opacity duration-200 motion-reduce:transition-none",
              open ? "opacity-100" : "opacity-0",
            )}
          >
            <dl className="grid gap-x-6 gap-y-1.5 sm:grid-cols-2">
              {rows.map((row) => (
                <div
                  key={row.label}
                  className="flex items-baseline justify-between gap-3 border-b border-line-subtle/60 pb-1 last:border-0 sm:last:border-0"
                >
                  <dt className="text-[11px] text-content-tertiary">{row.label}</dt>
                  <dd className="tabular text-[11px] text-content-secondary">{row.value}</dd>
                </div>
              ))}
            </dl>
          </div>
        </div>
      </div>
    </div>
  );
}
