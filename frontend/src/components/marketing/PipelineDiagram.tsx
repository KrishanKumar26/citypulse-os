/**
 * The path one reading takes, drawn once instead of described six times.
 *
 * The architecture section listed six layers and their justifications, which
 * says what each component is for and never says what happens to a record. A
 * reader wanting to know whether this is a real pipeline or a stack of nouns
 * had to assemble the order themselves.
 *
 * Stages are the ones the repository actually runs, in the order it runs them,
 * and the branch is drawn as a branch because that is what it is: dbt and
 * Airflow read the warehouse on their own schedule rather than sitting between
 * the loader and the API.
 *
 * The packets travel one stage each with a stagger, so the movement reads as a
 * record advancing rather than five unrelated things blinking. Under
 * prefers-reduced-motion they stop mid-edge, which still shows where they are.
 */

interface Stage {
  name: string;
  detail: string;
}

const STAGES: Stage[] = [
  { name: "Kafka", detail: "topics per signal, keyed by zone" },
  { name: "Spark", detail: "validate · window · aggregate" },
  { name: "PostgreSQL", detail: "curated warehouse" },
  { name: "Spring Boot", detail: "REST and server-sent events" },
  { name: "Next.js", detail: "command centre" },
];

const BOX_W = 152;
const BOX_H = 68;
const PITCH = 202;
// Boxes start near the top: the viewBox is cropped to the drawing, and the
// breathing room belongs to the card's padding rather than to dead SVG.
const ROW_Y = 20;
const CENTRE_Y = ROW_Y + BOX_H / 2;

export function PipelineDiagram() {
  return (
    <svg
      viewBox="0 0 960 184"
      className="h-auto w-full"
      role="presentation"
      aria-hidden="true"
    >
      {/* Edges under the boxes, so a connector never crosses a label. */}
      <g fill="none" stroke="var(--color-line-default)" strokeWidth="1">
        {STAGES.slice(0, -1).map((stage, index) => (
          <path
            key={`edge-${stage.name}`}
            d={`M ${index * PITCH + BOX_W} ${CENTRE_Y} H ${(index + 1) * PITCH}`}
          />
        ))}
        {/* The branch. Dashed because it is a different cadence, not a
            different direction: these read the warehouse on a schedule. */}
        <path
          d={`M ${2 * PITCH + BOX_W / 2} ${ROW_Y + BOX_H} V 128`}
          strokeDasharray="3 3"
        />
      </g>

      {STAGES.map((stage, index) => (
        <g key={stage.name}>
          <rect
            x={index * PITCH}
            y={ROW_Y}
            width={BOX_W}
            height={BOX_H}
            rx="8"
            fill="var(--color-surface-overlay)"
            stroke="var(--color-line-subtle)"
          />
          <text
            x={index * PITCH + BOX_W / 2}
            y={ROW_Y + 28}
            textAnchor="middle"
            className="fill-[var(--color-content-primary)] text-[13px] font-medium"
          >
            {stage.name}
          </text>
          <text
            x={index * PITCH + BOX_W / 2}
            y={ROW_Y + 47}
            textAnchor="middle"
            className="fill-[var(--color-content-tertiary)] text-[10px]"
          >
            {stage.detail}
          </text>
        </g>
      ))}

      <g>
        <rect
          x={2 * PITCH}
          y="128"
          width={BOX_W}
          height="44"
          rx="8"
          fill="var(--color-surface-raised)"
          stroke="var(--color-line-subtle)"
          strokeDasharray="3 3"
        />
        <text
          x={2 * PITCH + BOX_W / 2}
          y="148"
          textAnchor="middle"
          className="fill-[var(--color-content-secondary)] text-[12px]"
        >
          Airflow · dbt
        </text>
        <text
          x={2 * PITCH + BOX_W / 2}
          y="163"
          textAnchor="middle"
          className="fill-[var(--color-content-tertiary)] text-[10px]"
        >
          batch models and tests
        </text>
      </g>

      {/* Packets last so one is never hidden behind a box it has reached. */}
      <g>
        {STAGES.slice(0, -1).map((stage, index) => (
          <circle
            key={`packet-${stage.name}`}
            r="3"
            fill="var(--color-accent)"
            className="signal-dot"
            style={{
              offsetPath: `path("M ${index * PITCH + BOX_W} ${CENTRE_Y} H ${(index + 1) * PITCH}")`,
              animationDelay: `${index * 0.42}s`,
            }}
          />
        ))}
      </g>
    </svg>
  );
}
