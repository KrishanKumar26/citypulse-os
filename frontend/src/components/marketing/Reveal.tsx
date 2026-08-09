"use client";

import { useEffect, useRef, useState, type ReactNode } from "react";

import { cn } from "@/components/ui";

/**
 * Shows its children once they have been scrolled to.
 *
 * The landing page is nine full-height sections. Rendering all of them at once
 * is correct and reads as a wall; letting each arrive as it is reached gives
 * the page the reading order it already has in its markup.
 *
 * Observed rather than timed. A scroll-position listener fires on every frame
 * of every scroll and has to be throttled into approximating what
 * IntersectionObserver already computes off the main thread.
 *
 * It disconnects after the first intersection: this is an entrance, and an
 * element that faded back out when scrolled past would turn a page of content
 * into a page of animation.
 *
 * The stillness case is handled in CSS rather than here — `.reveal` under
 * `prefers-reduced-motion` is already at its final state, so a reader who has
 * asked for no motion sees the content whether or not this effect ever runs.
 */
export function Reveal({
  children,
  className,
  delay = 0,
  as: Tag = "div",
}: {
  children: ReactNode;
  className?: string;
  /** Milliseconds to stagger a sibling behind the one before it. */
  delay?: number;
  as?: "div" | "section" | "li";
}) {
  const ref = useRef<HTMLElement | null>(null);
  const [shown, setShown] = useState(false);

  useEffect(() => {
    const node = ref.current;
    if (!node) return;

    // No IntersectionObserver — an old browser, or a test environment. Showing
    // the content is the only safe failure: hiding it would leave a blank page
    // that never recovers.
    //
    // Scheduled rather than set here: the state cannot be seeded during render
    // because the server has no IntersectionObserver either, so a lazy initial
    // value would disagree with the client's and break hydration.
    if (typeof IntersectionObserver === "undefined") {
      const frame = requestAnimationFrame(() => setShown(true));
      return () => cancelAnimationFrame(frame);
    }

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          if (entry.isIntersecting) {
            setShown(true);
            observer.disconnect();
          }
        }
      },
      // A margin off the bottom so a section has begun its entrance by the time
      // it is properly in view, rather than starting as it arrives.
      { rootMargin: "0px 0px -12% 0px", threshold: 0.05 },
    );

    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  return (
    <Tag
      ref={ref as never}
      data-shown={shown}
      style={delay ? { transitionDelay: `${delay}ms` } : undefined}
      className={cn("reveal", className)}
    >
      {children}
    </Tag>
  );
}
