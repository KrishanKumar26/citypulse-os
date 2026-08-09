/**
 * The six stages, drawn as the loop the heading claims they are.
 *
 * The section is titled "a closed loop, not a reporting layer" and then showed
 * six cards in a grid, which is the shape of a reporting layer. The claim and
 * the picture disagreed, and the picture is what a reader believes.
 *
 * So: six stages in order, a return edge from Act back to Observe, and one
 * signal travelling the whole run. The return edge is dashed because it is a
 * different kind of link — an action changes the city, and the change is
 * observed on the next window rather than handed straight back.
 *
 * Every stage names something that exists. Recommend is response plans, Act is
 * alerts and the API; neither is aspiration.
 */

const STAGES = [
  "Observe",
  "Understand",
  "Predict",
  "Simulate",
  "Recommend",
  "Act",
];

const FIRST_X = 66;
const PITCH = 166;
const NODE_Y = 44;
const NODE_R = 21;

const x = (index: number) => FIRST_X + index * PITCH;

/** The straight run between two adjacent stages, clear of both nodes. */
const edge = (index: number) =>
  `M ${x(index) + NODE_R + 6} ${NODE_Y} H ${x(index + 1) - NODE_R - 6}`;

/** Act back to Observe, swung below the row so it crosses nothing. */
const RETURN_EDGE =
  `M ${x(5)} ${NODE_Y + NODE_R + 6} C ${x(5)} 124, ${x(0)} 124, ${x(0)} ${NODE_Y + NODE_R + 6}`;

export function ProcessLoop() {
  return (
    <svg
      viewBox="0 0 960 150"
      className="h-auto w-full"
      role="presentation"
      aria-hidden="true"
    >
      <g fill="none" stroke="var(--color-line-default)" strokeWidth="1">
        {STAGES.slice(0, -1).map((stage, index) => (
          <path key={`edge-${stage}`} d={edge(index)} />
        ))}
        <path d={RETURN_EDGE} strokeDasharray="4 4" />
      </g>

      <text
        x={(x(0) + x(5)) / 2}
        y="140"
        textAnchor="middle"
        className="fill-[var(--color-content-tertiary)] text-[10px]"
      >
        an action changes the city, and the change is observed on the next window
      </text>

      {STAGES.map((stage, index) => (
        <g key={stage}>
          <circle
            cx={x(index)}
            cy={NODE_Y}
            r={NODE_R}
            fill="var(--color-surface-overlay)"
            stroke="var(--color-line-subtle)"
          />
          <text
            x={x(index)}
            y={NODE_Y + 4}
            textAnchor="middle"
            className="fill-[var(--color-accent)] text-[11px] font-medium"
          >
            {index + 1}
          </text>
          <text
            x={x(index)}
            y={NODE_Y + NODE_R + 18}
            textAnchor="middle"
            className="fill-[var(--color-content-secondary)] text-[11px]"
          >
            {stage}
          </text>
        </g>
      ))}

      {/* One signal, handed along. The delays are the run: each packet leaves
          as the one before it arrives, so the eye follows a single thing
          through six stages rather than five blinking at once. */}
      <g>
        {STAGES.slice(0, -1).map((stage, index) => (
          <circle
            key={`packet-${stage}`}
            r="3"
            fill="var(--color-accent)"
            className="signal-dot"
            style={{
              offsetPath: `path("${edge(index)}")`,
              animationDelay: `${index * 0.5}s`,
            }}
          />
        ))}
      </g>
    </svg>
  );
}
