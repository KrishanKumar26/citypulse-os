/**
 * The hero visual: a real city on the left, one intelligence layer on the right.
 *
 * The zone positions are Delhi's, projected from the coordinates migration V3
 * seeds — equirectangular with a cos(latitude) correction, so the 22 km by
 * 12 km the six zones actually span is not stretched into whatever shape the
 * viewBox wanted. Connaught Place sits north of Saket here because it does in
 * Delhi.
 *
 * That matters more than it sounds. The alternative — six dots arranged for
 * balance — would be the first thing on the page, on a product whose entire
 * argument is that its figures can be pointed at. Drawing an invented city to
 * illustrate a real one is a small lie in exactly the place it costs most.
 *
 * WHAT IS NOT CLAIMED
 *
 * No zone carries a risk colour, a reading or a number. Those would have to be
 * invented: the landing page is public and every /api/v1 route needs a token,
 * so there is nothing live to draw. The nodes are the network, and the
 * intelligence layer beside them is what the platform does to it — a diagram
 * of the system, not a screenshot of it.
 *
 * Motion is three things, each of which is a fact: a packet travelling a link
 * is a reading arriving, a node pulsing is a zone reporting, and the hub's
 * slow breath is the layer working. The keyframes live in globals.css beside
 * their reduced-motion answers.
 */

interface Zone {
  name: string;
  code: string;
  x: number;
  y: number;
}

/** Delhi, projected. Generated from V3's centre coordinates — see the header. */
const ZONES: Zone[] = [
  { name: "Connaught Place", code: "DEL-CNP", x: 207.9, y: 129.7 },
  { name: "Nizamuddin", code: "DEL-NZM", x: 240.6, y: 182.0 },
  { name: "Okhla", code: "DEL-OKH", x: 262.0, y: 251.2 },
  { name: "Saket", code: "DEL-SKT", x: 196.8, y: 260.3 },
  { name: "IGI Airport", code: "DEL-IGI", x: 82.5, y: 220.0 },
  { name: "Dwarka", code: "DEL-DWK", x: 26.0, y: 177.7 },
];

/**
 * Which zones are drawn as connected.
 *
 * Not roads — the platform holds zone centres, not a road graph, and tracing
 * the ring road from memory would be inventing geography. These are the
 * corridors between adjacent zones, which is what the aggregation is keyed by.
 */
const LINKS: Array<[number, number]> = [
  [0, 1], [1, 2], [2, 3], [3, 0], [3, 4], [4, 5], [5, 0], [1, 3],
];

const HUB = { x: 430, y: 196, r: 40 };

/** What the layer produces. Three modules that exist and open. */
const ANSWERS = [
  { label: "Live conditions", y: 108 },
  { label: "Forecast", y: 196 },
  { label: "Simulation", y: 284 },
];

/** A zone's link into the layer, curved so six do not arrive as a fan of lines. */
function feedPath(zone: Zone): string {
  const midX = (zone.x + HUB.x - HUB.r) / 2;
  return `M ${zone.x} ${zone.y} C ${midX} ${zone.y}, ${midX} ${HUB.y}, ${HUB.x - HUB.r} ${HUB.y}`;
}

export function CityIntelligence() {
  return (
    <svg
      viewBox="0 0 620 400"
      className="h-auto w-full"
      role="img"
      aria-labelledby="hero-visual-title"
    >
      <title id="hero-visual-title">
        A diagram: six Delhi zones, drawn at their real positions and linked by
        corridors, feeding one intelligence layer that produces live conditions,
        forecasts and simulations.
      </title>

      <defs>
        <radialGradient id="city-hub-fill">
          <stop offset="0%" stopColor="var(--color-accent)" stopOpacity="0.2" />
          <stop offset="100%" stopColor="var(--color-accent)" stopOpacity="0.02" />
        </radialGradient>
        {/* The city's ground. A grid rather than a filled shape: the platform
            knows zone centres, and drawing a coastline it does not hold would
            be decoration pretending to be data. */}
        <pattern id="city-grid" width="26" height="26" patternUnits="userSpaceOnUse">
          <path
            d="M 26 0 L 0 0 0 26"
            fill="none"
            stroke="var(--color-line-subtle)"
            strokeWidth="0.5"
          />
        </pattern>
        <linearGradient id="city-grid-fade" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0%" stopColor="white" stopOpacity="0.55" />
          <stop offset="70%" stopColor="white" stopOpacity="0.12" />
          <stop offset="100%" stopColor="white" stopOpacity="0" />
        </linearGradient>
        <mask id="city-grid-mask">
          <rect x="0" y="56" width="320" height="280" fill="url(#city-grid-fade)" />
        </mask>
      </defs>

      <rect
        x="0"
        y="56"
        width="320"
        height="280"
        fill="url(#city-grid)"
        mask="url(#city-grid-mask)"
      />

      {/* Corridors between zones, then the links into the layer. */}
      <g fill="none" stroke="var(--color-line-default)" strokeWidth="1">
        {LINKS.map(([from, to]) => (
          <line
            key={`${from}-${to}`}
            x1={ZONES[from].x}
            y1={ZONES[from].y}
            x2={ZONES[to].x}
            y2={ZONES[to].y}
          />
        ))}
      </g>

      <g fill="none" stroke="var(--color-line-subtle)" strokeWidth="1">
        {ZONES.map((zone) => (
          <path key={`feed-${zone.code}`} d={feedPath(zone)} />
        ))}
      </g>

      {ZONES.map((zone, index) => (
        <g key={zone.code}>
          {/* Two circles: a soft halo that breathes and a solid centre that does
              not, so the node stays legible while it pulses. */}
          <circle
            cx={zone.x}
            cy={zone.y}
            r="7"
            fill="var(--color-accent)"
            fillOpacity="0.18"
            className="zone-pulse"
            style={{ ["--pulse-period" as string]: `${2.6 + index * 0.4}s` }}
          />
          <circle cx={zone.x} cy={zone.y} r="3" fill="var(--color-accent)" />
          <text
            x={zone.x}
            y={zone.y - 13}
            textAnchor="middle"
            className="fill-[var(--color-content-tertiary)] text-[9px]"
          >
            {zone.code}
          </text>
        </g>
      ))}

      <text
        x="26"
        y="76"
        className="fill-[var(--color-content-tertiary)] text-[10px] uppercase tracking-[0.12em]"
      >
        Delhi · 6 zones
      </text>

      <g>
        <circle cx={HUB.x} cy={HUB.y} r={HUB.r} fill="url(#city-hub-fill)" />
        <circle
          cx={HUB.x}
          cy={HUB.y}
          r={HUB.r}
          fill="none"
          stroke="var(--color-accent)"
          strokeOpacity="0.45"
        />
        <text
          x={HUB.x}
          y={HUB.y - 2}
          textAnchor="middle"
          className="fill-[var(--color-content-primary)] text-[12px] font-medium"
        >
          intelligence
        </text>
        <text
          x={HUB.x}
          y={HUB.y + 13}
          textAnchor="middle"
          className="fill-[var(--color-content-tertiary)] text-[10px]"
        >
          layer
        </text>
      </g>

      {/* Outputs. Lines first so a card paints over the one reaching it. */}
      <g fill="none" stroke="var(--color-line-default)" strokeWidth="1">
        {ANSWERS.map((answer) => (
          <path
            key={`out-${answer.label}`}
            d={`M ${HUB.x + HUB.r} ${HUB.y} C ${HUB.x + 62} ${HUB.y}, ${HUB.x + 66} ${answer.y}, 512 ${answer.y}`}
          />
        ))}
      </g>

      {ANSWERS.map((answer) => (
        <g key={answer.label}>
          <rect
            x="512"
            y={answer.y - 14}
            width="106"
            height="28"
            rx="6"
            fill="var(--color-surface-overlay)"
            stroke="var(--color-accent)"
            strokeOpacity="0.3"
          />
          <text
            x="565"
            y={answer.y + 4}
            textAnchor="middle"
            className="fill-[var(--color-content-primary)] text-[10px]"
          >
            {answer.label}
          </text>
        </g>
      ))}

      {/* Packets last, over everything they pass. */}
      <g>
        {ZONES.map((zone, index) => (
          <circle
            key={`packet-${zone.code}`}
            r="2.5"
            fill="var(--color-accent)"
            className="signal-dot"
            style={{
              offsetPath: `path("${feedPath(zone)}")`,
              animationDelay: `${index * 0.5}s`,
            }}
          />
        ))}
        {ANSWERS.map((answer, index) => (
          <circle
            key={`packet-out-${answer.label}`}
            r="2.5"
            fill="var(--color-accent)"
            className="signal-dot"
            style={{
              offsetPath: `path("M ${HUB.x + HUB.r} ${HUB.y} C ${HUB.x + 62} ${HUB.y}, ${HUB.x + 66} ${answer.y}, 512 ${answer.y}")`,
              animationDelay: `${0.8 + index * 0.6}s`,
            }}
          />
        ))}
      </g>
    </svg>
  );
}
