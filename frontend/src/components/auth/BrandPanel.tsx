import Link from "next/link";

import { ApiStatus } from "@/components/system/ApiStatus";
import { DATA_DISCLOSURE } from "@/lib/wording";

/**
 * What the visitor is signing into, shown beside the form.
 *
 * The sign-in screen used to be a 384px form centred in an otherwise empty
 * page: the first thing anyone saw of this product said nothing about it. This
 * panel is the half that does.
 *
 * **Everything here is true and none of it is live.** The obvious version of
 * this panel streams each city's current AQI down the left-hand side, and it
 * cannot: every `/api/v1` route requires a token, and there is no unauthorised
 * read to make. The alternative — hardcoding "Delhi 135" so it looks live — is
 * the exact failure the rest of this codebase is built to prevent, and it would
 * be committed on the one screen everybody sees. So the counts are structural
 * facts that change only when a migration changes them, the provenance legend
 * describes a rule rather than a reading, and the only live thing on the page
 * is labelled as what it is: whether the API answered.
 */

/**
 * The product's thesis in three lines.
 *
 * Chosen over a feature list because it is the thing a visitor cannot guess
 * from a screenshot, and it is what the rest of the interface spends its time
 * proving. The dots are the same tokens the dashboard uses for these states.
 */
const PROVENANCE = [
  {
    label: "Measured",
    dot: "bg-status-normal",
    detail: "A government monitoring station reported it",
  },
  {
    label: "Modelled",
    dot: "bg-info",
    detail: "Copernicus CAMS solved it — real air, no instrument here",
  },
  {
    label: "Synthetic",
    dot: "bg-status-moderate",
    detail: "Generated, where no real feed reaches",
  },
];

const SCALE = [
  { value: "10", label: "cities" },
  { value: "62", label: "zones" },
  { value: "14", label: "modules" },
];

export function BrandPanel() {
  return (
    <div className="relative flex h-full flex-col overflow-hidden bg-surface-base p-8 xl:p-12">
      {/*
        The same two washes the application ground uses, so signing in does not
        cross a visual boundary into a different product.
      */}
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10"
        style={{
          background:
            "radial-gradient(48rem 32rem at 8% -8%, color-mix(in oklab, var(--color-accent) 9%, transparent), transparent 68%)," +
            "radial-gradient(40rem 30rem at 96% 104%, color-mix(in oklab, var(--color-ai) 6%, transparent), transparent 66%)",
        }}
      />

      {/*
        One measure for all three blocks. Left to fill the panel, the headline
        ran to a dozen words a line on a wide monitor, and the stats row and the
        rule under it ended in different places.
      */}
      <div className="mx-auto flex h-full w-full max-w-[27rem] flex-col justify-between">
        <Link href="/" className="inline-flex items-center gap-2.5 self-start">
          <svg width="24" height="24" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <rect
              x="1.5"
              y="1.5"
              width="21"
              height="21"
              rx="5"
              stroke="var(--color-accent)"
              strokeWidth="1.5"
            />
            <path
              d="M5 14.5h3l2-5 2.5 8 2-6 1.5 3H19"
              stroke="var(--color-accent)"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          <span className="text-[15px] font-semibold tracking-tight">CityPulse OS</span>
        </Link>

        <div className="py-12">
          <p className="text-[11px] font-medium uppercase tracking-[0.14em] text-accent">
            Observe · Predict · Simulate · Act
          </p>

          <h1 className="mt-4 text-[30px] font-semibold leading-[1.15] tracking-tight text-content-primary xl:text-[34px]">
            Urban intelligence that only says what it can point at.
          </h1>

          <p className="mt-4 text-[14px] leading-relaxed text-content-secondary">
            Live city conditions, forecasts carrying their own measured error,
            and scenarios run against what was really observed.
          </p>

          <ul className="mt-8 space-y-3">
            {PROVENANCE.map((item) => (
              <li key={item.label} className="flex items-start gap-3">
                <span
                  aria-hidden="true"
                  className={`mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full ${item.dot}`}
                />
                <span className="text-[13px] leading-snug">
                  <span className="font-medium text-content-primary">{item.label}</span>
                  <span className="text-content-tertiary"> — {item.detail}</span>
                </span>
              </li>
            ))}
          </ul>
        </div>

        <div>
          <dl className="flex gap-8">
            {SCALE.map((item) => (
              <div key={item.label}>
                <dt className="sr-only">{item.label}</dt>
                <dd>
                  <span className="tabular text-[22px] font-semibold leading-none text-content-primary">
                    {item.value}
                  </span>
                  <span className="ml-1.5 text-[12px] text-content-tertiary">{item.label}</span>
                </dd>
              </div>
            ))}
          </dl>

          <div className="mt-6 border-t border-line-subtle pt-5">
            <ApiStatus coldStartHint />
          </div>

          <p className="mt-3 text-[11px] leading-relaxed text-content-tertiary">
            Demonstration environment. {DATA_DISCLOSURE} The real feeds are
            credited on the Data Sources screen.
          </p>
        </div>
      </div>
    </div>
  );
}
