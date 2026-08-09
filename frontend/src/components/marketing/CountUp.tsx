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

  // Starts at the answer, not at zero.
  //
  // Counting up from a zero initial state renders "0" into the server's HTML,
  // so the page ships a sentence reading "0 signal types correlated" — false,
  // and the one thing this product is not allowed to do. It is also what a
  // visitor with JavaScript disabled would be left holding.
  //
  // So the figure is correct from the first byte, and the effect below rewinds
  // it only when it is safe to: off-screen, where nobody watches it drop.
  const [value, setValue] = useState(to);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    const still =
      typeof window !== "undefined" &&
      window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

    if (still || typeof IntersectionObserver === "undefined") return;

    // Already on screen at mount — near the top of the page, or on a short
    // viewport. Rewinding here would show the reader the number falling to
    // zero and climbing back, which is worse than not animating at all.
    const box = node.getBoundingClientRect();
    if (box.top < window.innerHeight && box.bottom > 0) return;

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

    // Rewound off-screen, then armed. Both in a frame callback rather than in
    // the effect body: a synchronous write here is a cascading render.
    const rewind = requestAnimationFrame(() => {
      setValue(0);
      observer.observe(node);
    });
    return () => {
      observer.disconnect();
      cancelAnimationFrame(rewind);
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
