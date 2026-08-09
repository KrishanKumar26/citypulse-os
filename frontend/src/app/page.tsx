import Link from "next/link";

import { API_BASE_URL } from "@/lib/api/client";

import { CapabilityGlyph } from "@/components/marketing/CapabilityGlyph";
import { CountUp } from "@/components/marketing/CountUp";
import { Reveal } from "@/components/marketing/Reveal";
import { PipelineDiagram } from "@/components/marketing/PipelineDiagram";
import { ProcessLoop } from "@/components/marketing/ProcessLoop";
import { CityIntelligence } from "@/components/marketing/CityIntelligence";

/**
 * Landing page (PRD §6.1).
 *
 * Statically rendered and free of client JavaScript beyond navigation. Every
 * claim describes something that exists or is explicitly labelled as planned —
 * the product roadmap is presented as a roadmap, not as shipped capability.
 */

export default function LandingPage() {
  return (
    <div className="min-h-screen bg-surface-base">
      <SiteHeader />
      <main id="main-content">
        <Hero />
        <ProblemSection />
        <HowItWorks />
        <Capabilities />
        <ArchitectureSection />
        <SecuritySection />
        <RoadmapSection />
        <CallToAction />
      </main>
      <SiteFooter />
    </div>
  );
}

function SiteHeader() {
  return (
    <header className="sticky top-0 z-40 border-b border-line-subtle bg-surface-base/85 backdrop-blur">
      <div className="mx-auto flex h-14 max-w-6xl items-center justify-between px-6">
        <Link href="/" className="flex items-center gap-2.5">
          <Logo />
          <span className="text-[15px] font-semibold tracking-tight">CityPulse OS</span>
        </Link>
        <nav className="hidden items-center gap-7 text-[13px] text-content-secondary md:flex">
          <a href="#how-it-works" className="transition-colors hover:text-content-primary">How it works</a>
          <a href="#capabilities" className="transition-colors hover:text-content-primary">Capabilities</a>
          <a href="#architecture" className="transition-colors hover:text-content-primary">Architecture</a>
          <a href="#security" className="transition-colors hover:text-content-primary">Security</a>
          <a href="#roadmap" className="transition-colors hover:text-content-primary">Roadmap</a>
        </nav>
        <div className="flex items-center gap-2">
          <Link
            href="/login"
            className="rounded-md px-3 py-1.5 text-[13px] text-content-secondary transition-colors hover:bg-surface-hover hover:text-content-primary"
          >
            Sign in
          </Link>
          <Link
            href="/signup"
            className="rounded-md bg-accent px-3.5 py-1.5 text-[13px] font-medium text-white transition-colors hover:bg-accent-hover"
          >
            Create account
          </Link>
        </div>
      </div>
    </header>
  );
}

function Logo() {
  return (
    <svg width="22" height="22" viewBox="0 0 24 24" fill="none" aria-hidden="true">
      <rect x="1.5" y="1.5" width="21" height="21" rx="5" stroke="var(--color-accent)" strokeWidth="1.5" />
      <path d="M5 14.5h3l2-5 2.5 8 2-6 1.5 3H19" stroke="var(--color-accent)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function Hero() {
  return (
    <section className="border-b border-line-subtle">
      {/* Copy and diagram side by side from lg. Below that the diagram follows
          the buttons rather than splitting the sentence from its call to
          action — it restates the paragraph, so it can wait. */}
      <div className="mx-auto grid max-w-6xl gap-12 px-6 py-20 md:py-28 lg:grid-cols-[1fr_minmax(0,32rem)] lg:items-center lg:gap-16">
        <div>
        <div className="inline-flex items-center gap-2 rounded-full border border-line-default bg-surface-raised px-3 py-1 text-[12px] text-content-secondary">
          <span className="h-1.5 w-1.5 rounded-full bg-status-normal pulse-dot" aria-hidden="true" />
          Urban Intelligence Platform
        </div>

        <h1 className="mt-6 max-w-3xl text-4xl font-semibold leading-[1.1] tracking-tight md:text-[3.25rem] lg:text-[2.75rem] xl:text-[3.1rem]">
          An intelligence layer for
          <span className="block text-content-tertiary">understanding the city.</span>
        </h1>

        <p className="mt-6 max-w-2xl text-[15px] leading-relaxed text-content-secondary">
          Traffic, weather, air quality, incidents and events arrive as five
          separate feeds. CityPulse OS correlates them per zone and per window
          into one account of what is happening, what follows from it, and what
          a change would do — with every figure carrying where it came from.
        </p>

        <div className="mt-9 flex flex-wrap items-center gap-3">
          <Link
            href="/command-center"
            className="inline-flex h-11 items-center rounded-md bg-accent px-6 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
          >
            Open Command Centre
          </Link>
          <a
            href="#architecture"
            className="inline-flex h-11 items-center rounded-md border border-line-default bg-surface-overlay px-6 text-sm font-medium transition-colors hover:border-line-strong hover:bg-surface-hover"
          >
            Explore architecture
          </a>
        </div>

        {/* Says what the primary action does before it is taken. The Command
            Centre is behind authentication, so a visitor without an account
            lands on the sign-in form — better read here than discovered there. */}
        <p className="mt-4 text-[13px] text-content-tertiary">
          The Command Centre asks you to sign in; accounts are free and read-only.
          Air quality is real. Traffic, weather and incidents are synthetic and
          labelled as such throughout the product.
        </p>

        </div>

        <Reveal className="lg:pl-4">
          <CityIntelligence />
        </Reveal>
      </div>

      <div className="mx-auto max-w-6xl px-6 pb-20">
        <div className="grid grid-cols-2 gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle md:grid-cols-4">
          {[
            // Five, not six. ck_data_sources_type admits exactly TRAFFIC,
            // WEATHER, AIR_QUALITY, INCIDENT and CITY_EVENT — and the hero
            // diagram three hundred pixels above this row draws those five.
            // The page was contradicting itself within one screen.
            { count: 5, label: "Signal types correlated" },
            { count: 5, label: "Forecast horizons" },
            { count: 7, label: "Access roles" },
            { value: "API-first", label: "Every capability exposed" },
          ].map((stat) => (
            <div key={stat.label} className="bg-surface-raised px-5 py-6">
              <div className="text-2xl font-semibold tabular tracking-tight">
                {stat.count !== undefined ? <CountUp to={stat.count} /> : stat.value}
              </div>
              <div className="mt-1 text-[13px] text-content-tertiary">{stat.label}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function ProblemSection() {
  return (
    <section className="border-b border-line-subtle">
      <div className="mx-auto max-w-6xl px-6 py-20">
        <SectionLabel>The problem</SectionLabel>
        <h2 className="mt-3 max-w-2xl text-2xl font-semibold tracking-tight md:text-3xl">
          Cities generate enormous amounts of data that nothing connects.
        </h2>

        <div className="mt-10 grid gap-6 md:grid-cols-2">
          <Reveal className="rounded-lg border border-line-subtle bg-surface-raised p-6">
            <h3 className="text-sm font-medium text-content-secondary">Analysed independently</h3>
            <div className="mt-4 space-y-2.5">
              {[
                "Rainfall 18 mm/h",
                "Friday, 18:40",
                "Stadium event, 40,000 attending",
                "Vehicle density 94% of capacity",
              ].map((signal) => (
                <div key={signal} className="rounded border border-line-subtle bg-surface-overlay px-3 py-2 font-mono text-[13px] text-content-tertiary">
                  {signal}
                </div>
              ))}
            </div>
            <p className="mt-4 text-[13px] text-content-tertiary">
              Four separate readings. Four separate dashboards. No conclusion.
            </p>
          </Reveal>

          <Reveal delay={120} className="rounded-lg border border-accent/25 bg-accent-subtle p-6">
            <h3 className="text-sm font-medium text-accent">Correlated</h3>
            <div className="mt-4 rounded border border-line-default bg-surface-overlay p-4">
              <div className="text-[13px] text-content-tertiary">Assessment</div>
              <div className="mt-1.5 text-[15px] font-medium">
                High probability of a traffic surge in the next 30 minutes.
              </div>
              <div className="mt-4 grid grid-cols-3 gap-3 border-t border-line-subtle pt-4">
                {[
                  { label: "Traffic", value: "+37%" },
                  { label: "Delay", value: "+14 min" },
                  { label: "Parking", value: "+29%" },
                ].map((impact) => (
                  <div key={impact.label}>
                    <div className="text-[11px] uppercase tracking-wide text-content-tertiary">{impact.label}</div>
                    <div className="mt-0.5 text-sm font-semibold tabular text-status-high">{impact.value}</div>
                  </div>
                ))}
              </div>
            </div>
            <p className="mt-4 text-[13px] text-content-tertiary">
              Illustrative figures showing the shape of a correlation result. The
              correlation engine is live — measured correlations, with
              impliesCausation false in every payload.
            </p>
          </Reveal>
        </div>
      </div>
    </section>
  );
}

function HowItWorks() {
  const steps = [
    { step: "01", title: "Observe", body: "Traffic, weather, air quality, incidents and events stream continuously into a single pipeline." },
    { step: "02", title: "Understand", body: "Signals are validated, correlated and attributed to city zones, so conditions have explanations rather than only values." },
    { step: "03", title: "Predict", body: "Forecasts across five horizons, each reporting confidence derived from measured error." },
    { step: "04", title: "Simulate", body: "Run a hypothetical — heavy rain plus a 40,000-person event — and see the projected impact before it happens." },
    { step: "05", title: "Recommend", body: "Turn predictions into specific actions, each traceable to the data that produced it." },
    { step: "06", title: "Act", body: "Alerts, APIs and integrations so intelligence reaches the systems that respond." },
  ];

  return (
    <section id="how-it-works" className="border-b border-line-subtle">
      <div className="mx-auto max-w-6xl px-6 py-20">
        <SectionLabel>How it works</SectionLabel>
        <h2 className="mt-3 text-2xl font-semibold tracking-tight md:text-3xl">
          A closed loop, not a reporting layer.
        </h2>

        <Reveal className="mt-10 overflow-x-auto rounded-lg border border-line-subtle bg-surface-raised p-6">
          <div className="min-w-[44rem]">
            <ProcessLoop />
          </div>
        </Reveal>

        <div className="mt-4 grid gap-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle md:grid-cols-3">
          {steps.map((item, index) => (
            // Staggered in reading order. Six cards arriving together is a
            // wall; arriving in sequence is the order they happen in.
            <Reveal key={item.step} className="bg-surface-raised p-6" delay={index * 70}>
              <div className="font-mono text-[12px] text-accent">{item.step}</div>
              <h3 className="mt-2 text-[15px] font-medium">{item.title}</h3>
              <p className="mt-2 text-[13px] leading-relaxed text-content-tertiary">{item.body}</p>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

function Capabilities() {
  // Labelled by what is built, not by which phase was meant to build it. An
  // evaluator has two minutes (PRD §42) and needs to know what they can open
  // right now — a phase number answers a different question.
  const capabilities = [
    { title: "Live Intelligence", glyph: "live" as const, body: "Zone conditions streamed over server-sent events: traffic state, speed, air quality and composite risk, each citing the curated window it came from.", state: "Live" },
    { title: "Forecast Engine", glyph: "forecast" as const, body: "Congestion, speed, volume and risk across 15 minutes to 6 hours. Confidence is computed from the model's error on held-out data, not asserted.", state: "Live" },
    { title: "What-If Simulator", glyph: "simulation" as const, body: "Weather, events, closures and volume changes run against real observed conditions, with the engine's assumptions documented and unit tested.", state: "Live" },
    { title: "Anomaly Detection", glyph: "anomaly" as const, body: "Departures from what each zone normally does at this hour of the week — a learned baseline, not a fixed threshold.", state: "Live" },
    { title: "City Memory", glyph: "memory" as const, body: "Past situations and what actually followed them. When too few comparable situations exist, it says so rather than guessing.", state: "Live" },
    // Live, and the sentence is narrower than it was. The card claimed
    // "rate limiting for third-party access" and marked the whole thing
    // Planned, which was wrong in both directions: keys and the OpenAPI
    // document have shipped, and the rate limiter covers /api/v1/auth only —
    // it slows credential stuffing, not a key holder's traffic. Saying so is
    // cheaper than a reader discovering it against production.
    { title: "API Platform", glyph: "api" as const, body: "Documented OpenAPI surface and scoped API keys, authenticated on their own header and frozen to the permissions held when issued. Per-key rate limiting is not built; the limiter that exists guards the authentication endpoints.", state: "Live" },
  ];

  return (
    <section id="capabilities" className="border-b border-line-subtle">
      <div className="mx-auto max-w-6xl px-6 py-20">
        <SectionLabel>Capabilities</SectionLabel>
        <h2 className="mt-3 text-2xl font-semibold tracking-tight md:text-3xl">
          What the platform does.
        </h2>
        <p className="mt-3 max-w-2xl text-[15px] text-content-secondary">
          Each capability says whether it is built. Nothing below is presented as available
          before it exists.
        </p>

        <div className="mt-10 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {capabilities.map((capability, index) => (
            <Reveal
              key={capability.title}
              delay={index * 60}
              className="group rounded-lg border border-line-subtle bg-surface-raised p-5 transition-colors duration-300 hover:border-line-default"
            >
              <div className="flex items-start justify-between gap-3">
                <h3 className="text-[15px] font-medium">{capability.title}</h3>
                <span
                  className={`shrink-0 rounded border px-2 py-0.5 text-[11px] ${
                    capability.state === "Live"
                      ? "border-status-normal/40 bg-status-normal/10 text-status-normal"
                      : "border-line-default bg-surface-overlay text-content-tertiary"
                  }`}
                >
                  {capability.state}
                </span>
              </div>
              <p className="mt-2.5 text-[13px] leading-relaxed text-content-tertiary">{capability.body}</p>
              <div className="mt-4 border-t border-line-subtle pt-3">
                <CapabilityGlyph kind={capability.glyph} />
              </div>
            </Reveal>
          ))}
        </div>
      </div>
    </section>
  );
}

function ArchitectureSection() {
  const layers = [
    { name: "Ingestion", detail: "Kafka topics per signal type, keyed by zone for ordered processing" },
    { name: "Processing", detail: "Spark Structured Streaming — validation, windowing, feature generation" },
    { name: "Storage", detail: "S3 data lake with raw, processed, curated and feature layers; PostgreSQL warehouse" },
    { name: "Orchestration", detail: "Airflow for batch workflows; dbt models with data quality tests" },
    { name: "Application", detail: "Spring Boot modular monolith, REST plus server-sent events" },
    { name: "Presentation", detail: "Next.js command centre with map, metrics and simulation views" },
  ];

  return (
    <section id="architecture" className="border-b border-line-subtle">
      <div className="mx-auto max-w-6xl px-6 py-20">
        <SectionLabel>Architecture</SectionLabel>
        <h2 className="mt-3 text-2xl font-semibold tracking-tight md:text-3xl">
          Every component has a job.
        </h2>
        <p className="mt-3 max-w-2xl text-[15px] text-content-secondary">
          Technology is chosen for a stated reason and recorded in the architecture document. No
          component exists purely to lengthen a stack diagram.
        </p>

        <Reveal className="mt-10 overflow-x-auto rounded-lg border border-line-subtle bg-surface-raised p-6">
          <div className="min-w-[46rem]">
            <PipelineDiagram />
          </div>
        </Reveal>

        <div className="mt-4 overflow-hidden rounded-lg border border-line-subtle">
          {layers.map((layer, index) => (
            <div
              key={layer.name}
              className={`flex flex-col gap-1 bg-surface-raised px-5 py-4 md:flex-row md:items-center md:gap-6 ${
                index > 0 ? "border-t border-line-subtle" : ""
              }`}
            >
              <div className="w-36 shrink-0 text-sm font-medium">{layer.name}</div>
              <div className="text-[13px] text-content-tertiary">{layer.detail}</div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function SecuritySection() {
  const controls = [
    "BCrypt password hashing with a 12-round cost factor",
    "Short-lived JWT access tokens with rotating, revocable refresh tokens",
    "Refresh token reuse detection that revokes the entire session family",
    "Role-based access control enforced at the API, never only in the interface",
    "Account lockout, per-IP rate limiting and uniform responses that resist account enumeration",
    "Append-only audit log covering authentication, role and administrative actions",
  ];

  return (
    <section id="security" className="border-b border-line-subtle">
      <div className="mx-auto max-w-6xl px-6 py-20">
        <SectionLabel>Security</SectionLabel>
        <h2 className="mt-3 text-2xl font-semibold tracking-tight md:text-3xl">
          Enforced by the backend.
        </h2>
        <p className="mt-3 max-w-2xl text-[15px] text-content-secondary">
          The interface hides controls a user cannot use. The API refuses them regardless. Each
          control below is implemented and covered by automated tests.
        </p>

        <ul className="mt-10 grid gap-3 md:grid-cols-2">
          {controls.map((control) => (
            <li key={control} className="flex items-start gap-3 rounded-lg border border-line-subtle bg-surface-raised px-4 py-3.5">
              <svg className="mt-0.5 h-4 w-4 shrink-0 text-status-normal" viewBox="0 0 20 20" fill="none" stroke="currentColor" strokeWidth="1.75" aria-hidden="true">
                <path d="M4 10.5l4 4 8-9" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              <span className="text-[13px] leading-relaxed text-content-secondary">{control}</span>
            </li>
          ))}
        </ul>
      </div>
    </section>
  );
}

function RoadmapSection() {
  // Kept in step with docs/DEVELOPMENT_PLAN.md. A roadmap that still lists
  // shipped work as planned is worse than no roadmap: it tells an evaluator the
  // product does less than it does, and it is the first thing they read.
  const phases = [
    { phase: "Phase 0-1", title: "Architecture & backend foundation", status: "Complete" },
    { phase: "Phase 2", title: "Frontend foundation & command centre shell", status: "Complete" },
    { phase: "Phase 3", title: "Data platform: Kafka, Spark, data lake", status: "Complete" },
    { phase: "Phase 4", title: "Live intelligence & real-time map", status: "Complete" },
    { phase: "Phase 5", title: "Forecast engine", status: "Complete" },
    { phase: "Phase 6", title: "What-if simulator", status: "Complete" },
    { phase: "Phase 7", title: "Anomalies, city memory & correlation", status: "Complete" },
    // Both moved on the evidence rather than on the plan. Phase 8: ci.yml runs
    // the backend, frontend and data-platform suites plus security checks and
    // a compose smoke, and there are Dockerfiles for every service. Phase 9:
    // the platform is deployed and reachable, and the API surface is issuing
    // scoped keys — what is outstanding is the phase's performance and
    // accessibility criteria, which are unmeasured, so it is in progress
    // rather than complete.
    { phase: "Phase 8", title: "CI/CD, container builds & hardening", status: "Complete" },
    { phase: "Phase 9", title: "Cloud deployment, API platform & polish", status: "In progress" },
  ];

  return (
    <section id="roadmap" className="border-b border-line-subtle">
      <div className="mx-auto max-w-6xl px-6 py-20">
        <SectionLabel>Roadmap</SectionLabel>
        <h2 className="mt-3 text-2xl font-semibold tracking-tight md:text-3xl">
          Built in verifiable increments.
        </h2>
        <p className="mt-3 max-w-2xl text-[15px] text-content-secondary">
          The application builds, boots and passes its test suite at the end of every phase.
          Currently 698 automated checks across the backend, data platform, dbt models and
          frontend.
        </p>

        <div className="mt-10 space-y-px overflow-hidden rounded-lg border border-line-subtle bg-line-subtle">
          {phases.map((item) => (
            <div key={item.phase} className="flex items-center gap-4 bg-surface-raised px-5 py-3.5">
              <div className="w-24 shrink-0 font-mono text-[12px] text-content-tertiary">{item.phase}</div>
              <div className="flex-1 text-[13px] text-content-secondary">{item.title}</div>
              <StatusPill status={item.status} />
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function StatusPill({ status }: { status: string }) {
  const styles: Record<string, string> = {
    Complete: "border-status-normal/25 bg-status-normal-bg text-status-normal",
    Next: "border-accent/25 bg-accent-subtle text-accent",
    "In progress": "border-accent/25 bg-accent-subtle text-accent",
    Planned: "border-line-default bg-surface-overlay text-content-tertiary",
  };
  return (
    <span className={`shrink-0 rounded border px-2 py-0.5 text-[11px] font-medium ${styles[status]}`}>
      {status}
    </span>
  );
}

function CallToAction() {
  return (
    <section className="border-b border-line-subtle">
      <div className="mx-auto max-w-6xl px-6 py-20 text-center">
        <h2 className="text-2xl font-semibold tracking-tight md:text-3xl">
          Observe. Predict. Simulate. Act.
        </h2>
        <p className="mx-auto mt-3 max-w-xl text-[15px] text-content-secondary">
          Ten Indian metros, sixty-two zones, fourteen modules. Air quality is
          real; everything else is generated and says so.
        </p>
        <div className="mt-8 flex flex-wrap justify-center gap-3">
          <Link
            href="/command-center"
            className="inline-flex h-11 items-center rounded-md bg-accent px-6 text-sm font-medium text-white transition-colors hover:bg-accent-hover"
          >
            Open Command Centre
          </Link>
          <a
            href={`${API_BASE_URL}/swagger-ui.html`}
            target="_blank"
            rel="noreferrer"
            className="inline-flex h-11 items-center rounded-md border border-line-default bg-surface-overlay px-6 text-sm font-medium transition-colors hover:border-line-strong hover:bg-surface-hover"
          >
            API documentation
          </a>
        </div>
      </div>
    </section>
  );
}

function SiteFooter() {
  return (
    <footer>
      <div className="mx-auto max-w-6xl px-6 py-10">
        <div className="flex flex-col gap-6 md:flex-row md:items-start md:justify-between">
          <div className="flex items-center gap-2.5">
            <Logo />
            <span className="text-[13px] text-content-tertiary">
              CityPulse OS — Observe. Predict. Simulate. Act.
            </span>
          </div>

          <nav aria-label="Footer" className="flex flex-wrap gap-x-6 gap-y-2 text-[13px]">
            {[
              { href: "#how-it-works", label: "How it works" },
              { href: "#capabilities", label: "Capabilities" },
              { href: "#architecture", label: "Architecture" },
              { href: "#security", label: "Security" },
              { href: "#roadmap", label: "Status" },
            ].map((link) => (
              <a
                key={link.href}
                href={link.href}
                className="text-content-tertiary transition-colors hover:text-content-primary"
              >
                {link.label}
              </a>
            ))}
            <Link
              href="/login"
              className="text-content-tertiary transition-colors hover:text-content-primary"
            >
              Sign in
            </Link>
            <Link
              href="/signup"
              className="text-content-tertiary transition-colors hover:text-content-primary"
            >
              Create account
            </Link>
          </nav>
        </div>

        <p className="mt-8 border-t border-line-subtle pt-6 text-[12px] leading-relaxed text-content-disabled">
          Demonstration environment. Traffic, weather, incidents and city events
          are synthetic and labelled as demo data throughout the product. Air
          quality is real and credited on the Data Sources screen.
        </p>
      </div>
    </footer>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <div className="text-[12px] font-medium uppercase tracking-[0.12em] text-accent">{children}</div>
  );
}
