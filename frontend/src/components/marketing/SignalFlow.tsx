/**
 * The hero graphic: five feeds arriving, one thing coming out of them.
 *
 * It draws the sentence beside it. The copy says four readings are not four
 * unrelated data points, and until now the page asserted that in prose on an
 * otherwise empty screen — twelve hundred pixels of a city-intelligence product
 * with no city in it.
 *
 * The five inputs are the five source types the platform actually registers
 * (`data_sources.source_type`), and the three outputs are three modules that
 * exist. Nothing here is a category invented to balance the diagram.
 *
 * Pure SVG and CSS. A motion library for one looping animation would cost more
 * transferred bytes than every other dependency on this page, and `offset-path`
 * does the whole job: each packet declares the edge it belongs to, and the
 * keyframes live in globals.css with the reduced-motion answer beside them.
 *
 * No `<title>` and `aria-hidden`: the diagram restates the paragraph it sits
 * next to, so announcing it again would make a screen reader read the same
 * claim twice.
 */

interface Edge {
  /** The path the line and its packet both follow. */
  d: string;
  label: string;
  /** Seconds of offset, so packets do not arrive as a rank. */
  delay: number;
}

const HUB = { x: 300, y: 196, r: 42 };

const FEEDS: Edge[] = [
  { label: "Traffic", d: "M 112 56 C 196 56, 214 196, 256 196", delay: 0 },
  { label: "Weather", d: "M 112 126 C 196 126, 218 196, 256 196", delay: 0.55 },
  { label: "Air quality", d: "M 112 196 C 180 196, 200 196, 256 196", delay: 1.1 },
  { label: "Incidents", d: "M 112 266 C 196 266, 218 196, 256 196", delay: 1.7 },
  { label: "Events", d: "M 112 336 C 196 336, 214 196, 256 196", delay: 2.25 },
];

const ANSWERS: Edge[] = [
  { label: "Now", d: "M 344 196 C 396 196, 404 126, 452 126", delay: 0.3 },
  { label: "Next 30 min", d: "M 344 196 C 400 196, 408 196, 452 196", delay: 0.9 },
  { label: "What if", d: "M 344 196 C 396 196, 404 266, 452 266", delay: 1.5 },
];

/** Feed rows, left column. */
const FEED_Y = [56, 126, 196, 266, 336];
/** Answer rows, right column. */
const ANSWER_Y = [126, 196, 266];

export function SignalFlow() {
  return (
    <svg
      viewBox="0 0 560 400"
      className="h-auto w-full"
      role="presentation"
      aria-hidden="true"
    >
      <defs>
        {/* The hub's ground: brighter at its centre so it reads as the thing
            everything is arriving at, without a glow around it. */}
        <radialGradient id="hub-fill">
          <stop offset="0%" stopColor="var(--color-accent)" stopOpacity="0.22" />
          <stop offset="100%" stopColor="var(--color-accent)" stopOpacity="0.03" />
        </radialGradient>
      </defs>

      {/* Edges first, so every node paints over the line reaching it. */}
      <g fill="none" stroke="var(--color-line-default)" strokeWidth="1">
        {[...FEEDS, ...ANSWERS].map((edge) => (
          <path key={edge.label} d={edge.d} />
        ))}
      </g>

      {/* Feeds, as tiles rather than bare labels: the input is a source, and a
          source in this product is a row with a name and a state. */}
      {FEEDS.map((feed, index) => (
        <g key={feed.label}>
          <rect
            x="0"
            y={FEED_Y[index] - 15}
            width="112"
            height="30"
            rx="6"
            fill="var(--color-surface-overlay)"
            stroke="var(--color-line-subtle)"
          />
          <circle
            cx="16"
            cy={FEED_Y[index]}
            r="3"
            fill="var(--color-accent-mark)"
            className="zone-pulse"
            style={{ ["--pulse-period" as string]: `${2.4 + index * 0.35}s` }}
          />
          <text
            x="28"
            y={FEED_Y[index] + 4}
            className="fill-[var(--color-content-secondary)] text-[11px]"
          >
            {feed.label}
          </text>
        </g>
      ))}

      <g>
        <circle cx={HUB.x} cy={HUB.y} r={HUB.r} fill="url(#hub-fill)" />
        <circle
          cx={HUB.x}
          cy={HUB.y}
          r={HUB.r}
          fill="none"
          stroke="var(--color-accent)"
          strokeOpacity="0.5"
        />
        <text
          x={HUB.x}
          y={HUB.y + 4}
          textAnchor="middle"
          className="fill-[var(--color-content-primary)] text-[12px] font-medium"
        >
          correlate
        </text>
        {/* Below the circle, not inside it. At this radius the qualifier is
            wider than the node, and it was crossing its own border. */}
        <text
          x={HUB.x}
          y={HUB.y + HUB.r + 18}
          textAnchor="middle"
          className="fill-[var(--color-content-tertiary)] text-[10px]"
        >
          per zone, per window
        </text>
      </g>

      {/* Answers. Bordered in accent rather than filled: they are the output of
          the hub, and filling them would make three of them compete with it. */}
      {ANSWERS.map((answer, index) => (
        <g key={answer.label}>
          <rect
            x="452"
            y={ANSWER_Y[index] - 15}
            width="108"
            height="30"
            rx="6"
            fill="var(--color-surface-overlay)"
            stroke="var(--color-accent)"
            strokeOpacity="0.35"
          />
          <text
            x="506"
            y={ANSWER_Y[index] + 4}
            textAnchor="middle"
            className="fill-[var(--color-content-primary)] text-[11px]"
          >
            {answer.label}
          </text>
        </g>
      ))}

      {/* Packets last, over everything, so one is never hidden behind a tile. */}
      <g>
        {[...FEEDS, ...ANSWERS].map((edge) => (
          <circle
            key={`packet-${edge.label}`}
            r="2.5"
            fill="var(--color-accent)"
            className="signal-dot"
            style={{
              offsetPath: `path("${edge.d}")`,
              animationDelay: `${edge.delay}s`,
            }}
          />
        ))}
      </g>
    </svg>
  );
}
