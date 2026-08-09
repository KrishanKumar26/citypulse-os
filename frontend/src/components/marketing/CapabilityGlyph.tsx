/**
 * A small drawing of what each capability does, revealed on hover.
 *
 * Six cards of identical prose is a list; a reader scanning it takes the same
 * time on each and remembers none. A glyph gives every card a silhouette, and
 * the shapes are the actual claims — a forecast that fans out as it goes, an
 * anomaly that is a departure from a baseline rather than a threshold crossing,
 * a simulation that is a comparison against something observed.
 *
 * At rest they sit in the muted ramp and hold their shape. Hover raises them to
 * the accent and reveals the second half — the projection, the ring around the
 * outlier, the scenario bar. Nothing moves on its own: six looping animations
 * in one viewport would compete with each other and with the reading.
 *
 * No numbers. These are shapes of behaviour, not readings, and a plausible
 * y-axis here would be a figure nobody measured.
 */

export type GlyphKind =
  | "live"
  | "forecast"
  | "simulation"
  | "anomaly"
  | "memory"
  | "api";

/** Muted at rest, accent on hover — set on the card via `group`. */
const LINE = "stroke-[var(--color-content-disabled)] transition-colors duration-300 group-hover:stroke-[var(--color-accent)]";
const FILL = "fill-[var(--color-content-disabled)] transition-colors duration-300 group-hover:fill-[var(--color-accent)]";
/** The half that only appears on hover. */
const REVEAL = "opacity-0 transition-opacity duration-300 group-hover:opacity-100";

export function CapabilityGlyph({ kind }: { kind: GlyphKind }) {
  return (
    <svg viewBox="0 0 120 44" className="h-11 w-[120px]" role="presentation" aria-hidden="true">
      {kind === "live" && (
        <g className={LINE} strokeWidth="2" strokeLinecap="round">
          {/* A live signal: bars at the cadence of a stream, the newest tallest. */}
          {[8, 22, 36, 50, 64, 78, 92].map((x, index) => (
            <line
              key={x}
              x1={x}
              y1={34}
              x2={x}
              y2={34 - [10, 18, 8, 24, 14, 28, 20][index]}
            />
          ))}
          <circle cx="106" cy="22" r="3" className={`${FILL} ${REVEAL}`} stroke="none" />
        </g>
      )}

      {kind === "forecast" && (
        <g fill="none" strokeWidth="2" strokeLinecap="round">
          {/* Observed, then a projection that widens — confidence falls with
              the horizon, which is the one thing this engine insists on. */}
          <path className={LINE} d="M 6 30 L 22 26 L 38 30 L 54 20" />
          <path
            className={`${LINE} ${REVEAL}`}
            strokeDasharray="3 3"
            d="M 54 20 L 74 16 L 96 12"
          />
          <path
            className={`${REVEAL} fill-[var(--color-accent)]/12`}
            stroke="none"
            d="M 54 20 L 96 4 L 96 24 Z"
          />
        </g>
      )}

      {kind === "simulation" && (
        <g>
          {/* Two bars: what was observed, and what the scenario does to it. */}
          <rect x="18" y="18" width="26" height="16" rx="2" className={FILL} fillOpacity="0.5" />
          <rect
            x="58"
            y="8"
            width="26"
            height="26"
            rx="2"
            className={`${FILL} ${REVEAL}`}
          />
          <line
            x1="10"
            y1="34"
            x2="98"
            y2="34"
            className={LINE}
            strokeWidth="1"
            strokeOpacity="0.6"
          />
        </g>
      )}

      {kind === "anomaly" && (
        <g fill="none" strokeWidth="2" strokeLinecap="round">
          {/* A baseline that is a band, and one point outside it. Not a line
              crossing a threshold: the claim is a departure from normal. */}
          <path
            className={LINE}
            strokeWidth="1"
            strokeDasharray="2 3"
            d="M 6 18 H 108 M 6 30 H 108"
          />
          <path className={LINE} d="M 6 26 L 24 23 L 42 27 L 60 8 L 78 25 L 96 24" />
          <circle
            cx="60"
            cy="8"
            r="6"
            className={`${LINE} ${REVEAL}`}
            strokeWidth="1.5"
          />
        </g>
      )}

      {kind === "memory" && (
        <g>
          {/* Past situations, and the one being matched against them. */}
          {[
            [14, 26], [28, 16], [40, 30], [54, 20], [66, 28], [80, 14],
          ].map(([cx, cy]) => (
            <circle key={`${cx}-${cy}`} cx={cx} cy={cy} r="3" className={FILL} fillOpacity="0.55" />
          ))}
          <circle cx="102" cy="22" r="4" className={`${FILL} ${REVEAL}`} />
          <path
            className={`${LINE} ${REVEAL}`}
            fill="none"
            strokeWidth="1"
            strokeDasharray="2 3"
            d="M 84 18 H 96"
          />
        </g>
      )}

      {kind === "api" && (
        <g fill="none" strokeWidth="2" strokeLinecap="round">
          {/* A scoped request going out, an answer coming back. */}
          <rect x="4" y="12" width="24" height="20" rx="3" className={LINE} strokeWidth="1.5" />
          <rect x="92" y="12" width="24" height="20" rx="3" className={LINE} strokeWidth="1.5" />
          <path className={LINE} d="M 32 18 H 84" />
          <path className={`${LINE} ${REVEAL}`} strokeDasharray="3 3" d="M 88 28 H 36" />
          <path className={`${LINE} ${REVEAL}`} d="M 42 24 L 36 28 L 42 32" />
        </g>
      )}
    </svg>
  );
}
