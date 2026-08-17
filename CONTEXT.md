# CityPulse OS — Project Context

Written for whoever picks this up next, human or model. It describes what the
system is, how it is put together, and what it does and does not claim. Every
figure here was read out of the repository or measured against the deployment
on 11 August 2026, not recalled.

Read `MEMORY.md` beside this file for the decisions, traps and hard-won
knowledge that the code alone does not explain.

---

## 1. What this is

An urban intelligence platform for Indian metros. It ingests five kinds of city
signal, correlates them per zone and per five-minute window, and answers three
questions: what is happening, what follows from it, and what a change would do.

The product's distinguishing property — and the thing to preserve above all
else — is that **every figure can be pointed at**. Each number carries where it
came from, and the interface distinguishes "measured as zero" from "never
measured". If you change nothing else about how this project is worked on, keep
that.

Live at **https://citypulse-os-two.vercel.app**

## 2. Stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot, modular monolith, REST + server-sent events |
| Frontend | Next.js 16.3, React 19.2, TypeScript 5, Tailwind v4 |
| Database | PostgreSQL, Flyway migrations (V1–V21) |
| Data platform | Python 3.12, Kafka, Spark Structured Streaming, dbt, MinIO/S3 |
| Deployment | Vercel (frontend), Render free tier (backend), Neon free tier (Postgres) |
| CI | GitHub Actions — `ci.yml`, `refresh-demo-data.yml`, `backfill-baselines.yml` |

## 3. Repository layout

```
backend/           Spring Boot modular monolith
  src/main/java/com/citypulse/
    alert/ apikey/ audit/ auth/ common/ forecast/ geo/ intelligence/
    intervention/ notification/ response/ security/ simulation/
    telemetry/ user/
  src/main/resources/db/migration/   V1 … V21
frontend/          Next.js app router
  src/app/(app)/   the 14 signed-in modules
  src/app/(auth)/  login, signup, password reset
  src/app/page.tsx the public landing page
  src/components/  ui/ layout/ live/ charts/ map/ marketing/ auth/
  src/lib/         api/ theme.ts wording.ts situation-language.ts provenance.ts
data-engineering/  Python: generator, ingest, pipeline, intelligence, ml, dbt
docs/              PRD.md ARCHITECTURE.md API.md DATA_PIPELINE.md
                   DEPLOYMENT.md SECURITY.md ML.md PRD_COVERAGE.md
infrastructure/ docker/ monitoring/ ml/ scripts/ tests/
```

## 4. Data model, in one paragraph

Ten cities, sixty-two zones. Raw events land in `traffic_events`,
`air_quality_events`, `weather_events`, `incident_events` and `city_events`.
The pipeline folds them into `zone_metrics` — one row per zone per five-minute
window — which is what every screen reads, what baselines are learned from and
what forecasts are scored against. `anomalies`, `forecasts`, `alerts`,
`zone_baselines`, `forecast_accuracy` and `interventions` hang off that.

**`zone_metrics` is the platform's memory, and it is kept for thirty days.** It
was exempt from retention entirely until 16 August 2026, when it reached 263 MB
of a 489 MB database and the deployment stopped accepting writes with every
other table already pruned.

Thirty days is derived, not guessed. `intelligence/detection.py` buckets
baselines by hour of week — 168 buckets — and requires
`MIN_BASELINE_SAMPLES = 12`. A bucket collects one hour per week, which at
five-minute windows is exactly twelve samples, so **one week is the floor** and a
month leaves four times it. `pipeline/prune.py` refuses `--curated-days` under
seven rather than warning, because below it the detector stops judging windows
and the map simply looks calm.

What this costs is history no baseline reads: charts and the accuracy screen
cannot look back beyond a month.

## 5. Which data is real

This is the single most important thing to get right when changing copy.

| Feed | Source | Provenance |
|---|---|---|
| Air quality | Copernicus CAMS via Open-Meteo; CPCB stations via WAQI | `MODELLED` / `MEASURED` |
| Weather | Open-Meteo forecast model | `MODELLED` |
| Traffic | TomTom vehicle probes; this repository's generator where no road answers | `MEASURED` / `MODELLED` / `SYNTHETIC` |
| Incidents | generator | `SYNTHETIC` |
| City events | generator | `SYNTHETIC` |

Measured on the deployment, 17 August 2026, newest window per zone:

| Feed | Measured | Modelled | Synthetic |
|---|---|---|---|
| Traffic | 59 | 1 | 2 |
| Air | 10 | 52 | 0 |
| Weather | 0 | 62 | 0 |

Traffic stopped being generated on 16 August 2026 — see migration V22, and note
that a window carrying a real feed reports `speed_ratio` and has **no**
`occupancy_ratio`, because nothing counted the vehicles on that road.

Incidents and city events are still generated because **no free real-time feed
exists for them in these cities**, and PRD §43 requires the platform to run with
no external API at all. That is a design decision, not a gap.

The sentence describing this mix is defined **once**, in
`frontend/src/lib/wording.ts` as `DATA_DISCLOSURE`. Everything that describes the
feeds imports it. It was previously hand-written in five files and four of them
went stale the day weather became real — see `MEMORY.md`.

### The three provenances

`MEASURED` (an instrument reported it) / `MODELLED` (a physical model of the real
atmosphere — real conditions, no instrument here) / `SYNTHETIC` (generated).
`null` is a fourth state meaning no reading at all, and must never render as
zero or as "synthetic".

On screen these appear as plain phrases — "from a sensor", "from a forecast
model", "demo data" — with the exact term and its definition on hover. See
`lib/provenance.ts`.

## 6. Running it locally

```bash
# Postgres must be running and .env sourced; credentials are never defaulted.
set -a && . ./.env && set +a

cd backend && ./mvnw spring-boot:run          # :8080
cd frontend && npm install && npm run dev     # :3000

cd data-engineering
python -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m generator.main --sink jsonl --out /tmp/e.jsonl --no-realtime \
  --seed 42 --tick-seconds 100 --simulate-from … --simulate-to …
.venv/bin/python -m pipeline.local_runner --input /tmp/e.jsonl
```

Environment variables are listed in `.env`; the ones that matter are
`CITYPULSE_DB_URL`, `CITYPULSE_DB_USER`, `CITYPULSE_DB_PASSWORD`,
`CITYPULSE_JWT_SECRET`, `NEXT_PUBLIC_API_BASE_URL`. CI additionally needs the
repository secrets `HOSTED_PG_DSN` and `WAQI_API_TOKEN`.

## 7. Tests and validation

| Suite | Count | Command |
|---|---|---|
| Backend unit | 87 | `cd backend && ./mvnw test` |
| Backend integration | 162 | `cd backend && ./mvnw verify` |
| Data platform | 277 | `cd data-engineering && .venv/bin/python -m pytest tests/` |
| Frontend | 124 | `cd frontend && npx vitest run` |

Also `npx tsc --noEmit`, `npm run lint`, `npm run build` in `frontend/`.

The integration suite needs a clean `citypulse_test` database:
`dropdb citypulse_test && createdb -O citypulse citypulse_test`.

## 8. API

51 documented paths under `/api/v1`, OpenAPI at `/v3/api-docs`, Swagger at
`/swagger-ui.html`. Groups: `auth(9) live(6) cities(5) alerts(4) anomalies(4)
users(4) api-keys(3) forecasts(3) response-plans(3) data-sources(2)
simulations(2) zones(2) audit-logs(1) interventions(1) meta(1) roles(1)`.

Every route except `/auth/*` and `/actuator/health` requires a bearer token.
Seven roles; permissions are enforced by the API independently of the interface.

**Parameter names bite.** `GET /api/v1/alerts` takes `cityId`, not `citySlug`,
and its status enum is `NEW | ACKNOWLEDGED | INVESTIGATING | RESOLVED` — there is
no `OPEN`. Unknown parameters are ignored rather than rejected, so a wrong one
returns an empty list that looks like an empty database. This cost two hours
once; see `MEMORY.md`.

## 9. The scheduled pipeline

`refresh-demo-data.yml` runs hourly:

1. **Prune** raw events past 3 days and forecasts past 7, then `VACUUM FULL`
2. Generate 3 hours of telemetry at a 100-second tick, load via `local_runner`
3. Ingest measured air (WAQI), modelled air (Open-Meteo), modelled weather
4. Issue forecasts, detect anomalies
5. Rebuild stored anomaly sentences in the current wording
6. Report what landed

Order matters and is documented in the workflow itself. Prune is **first**
because it is the step that frees the space the load needs.

## 10. Known limitations, stated plainly

- **Digital Twin module is not built.** Declared as such in the README and the
  roadmap. Do not present it as available.
- **Traffic, incidents and city events are generated.** See §5.
- **Phase 9 is in progress**: performance and accessibility have not been
  measured or documented. Everything else in the roadmap is complete.
- **Confidence is not the issue here** — this project has no missing scoring
  spec. Forecast confidence is computed from measured error on held-out data.
- **The database filled, and the retention answer is now in place.** It reached
  Neon's 512 MB ceiling on 16 August 2026 — five days after being measured at
  399 MB, not the month that was predicted, because the refresh writes three
  hours of telemetry every hour. `zone_metrics` now carries a thirty-day
  retention (see §4). If it fills again the remaining options are unchanged:
  pay for Neon, or shorten retention toward the seven-day floor.
- **Cold start.** Render's free tier suspends the container after fifteen
  minutes idle; waking it has been measured at 63–104 seconds. The login form
  explains this after four seconds of waiting. It is not a fault.
- **Two dead files are still tracked** and referenced by nothing:
  `frontend/src/components/marketing/SignalFlow.tsx` and
  `data-engineering/pipeline/air_provenance.py` (superseded by
  `pipeline/provenance.py`). Safe to delete.

## 11. Conventions that are not obvious

- **Comments explain why, not what.** The codebase carries a lot of them and
  they are load-bearing: most record a decision, a measurement or a bug that a
  future reader would otherwise re-introduce. Match that register.
- **Colours are measured, never chosen by eye.** Contrast ratios and ΔE
  separations are recorded in `frontend/src/app/globals.css` beside the tokens.
  Both themes were built this way.
- **Two registers of language.** Screens aimed at operators and citizens use
  plain words; Data Health, Data Sources, API Management, Architecture and
  Security keep their technical vocabulary, because that is what their reader
  came for. This is audience-matching, not inconsistency.
- **Never invent a number to fill a gap.** "No reading", "No forecast for this
  measure" and "Nothing suggested yet" are correct answers. A dashboard that
  smooths over an absence is the one an operator learns to distrust.
