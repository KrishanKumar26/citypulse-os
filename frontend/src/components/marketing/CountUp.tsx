"use client";

import { useEffect, useRef, useState } from "react";

/**
 * Counts a figure up once it is scrolled to.
 *
 * Worth the code only because these particular numbers are the claim: six
 * signal types, five horizons, seven roles. Counting draws the eye to the
 * figure rather than to the label beside it, which is the right way round.
 *
 * Driven by requestAnimationFrame against a timestamp rather than by an
 * interval. An interval assumes every tick is the same length, so a busy main
 * thread stretches the count instead of dropping frames of it, and four
 * counters started together finish at visibly different moments.
 *
 * Reduced motion is answered by not animating at all — the final value is
 * rendered on mount. The CSS classes cannot help here because the number itself
 * is the thing that moves.
 */
const DURATION_MS = 900;

/** Ease-out cubic: fast at the start, settling rather than stopping. */
const ease = (t: number) => 1 - Math.pow(1 - t, 3);

export function CountUp({ to, className }: { to: number; className?: string }) {
  const ref = useRef<HTMLSpanElement | null>(null);
  const [value, setValue] = useState(0);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    const still =
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

    // Scheduled rather than set here, for the same reason as Reveal: the
    // server can answer neither question, so the value cannot be seeded during
    // render without disagreeing with the markup it hydrates.
    if (still || typeof IntersectionObserver === "undefined") {
      const settled = requestAnimationFrame(() => setValue(to));
      return () => cancelAnimationFrame(settled);
    }

    let frame = 0;
    let start: number | null = null;

    const step = (now: number) => {
      start ??= now;
      const progress = Math.min((now - start) / DURATION_MS, 1);
      setValue(Math.round(ease(progress) * to));
      if (progress < 1) frame = requestAnimationFrame(step);
    };

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            frame = requestAnimationFrame(step);
            observer.disconnect();
          }
        }
      },
      { threshold: 0.4 },
    );

    observer.observe(node);
    return () => {
      observer.disconnect();
      cancelAnimationFrame(frame);
    };
  }, [to]);

  return (
    // aria-label carries the final value throughout, so a screen reader
    // announces the figure rather than whatever the count happened to reach.
    <span ref={ref} className={className} aria-label={String(to)}>
      <span aria-hidden="true">{value}</span>
    </span>
  );
}
