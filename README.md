# CityPulse OS

**Observe. Predict. Simulate. Act.**

An urban intelligence platform for Indian metros. It ingests city telemetry,
turns it into curated conditions, forecasts where those conditions are heading,
lets an operator test hypothetical scenarios against them, and flags departures
from what a place normally does.

Built to the product requirements in [`docs/PRD.md`](docs/PRD.md). Phases 0–7 of
[the plan](docs/DEVELOPMENT_PLAN.md) are complete and verified. Phase 8 (CI,
containers, deployment) has not been started. Several Phase 9 surfaces have
shipped — API key management, demo-mode labelling, the analytics and data
screens — but the phase is not closed: its performance and accessibility exit
criteria remain unmeasured, so it is still listed as pending.

---

## What actually works

Fourteen modules are live across ten Indian metros. Digital Twin is the one
planned module that is not built, and nothing in the interface pretends
otherwise.

| Module | What it does |
|---|---|
| Command Center | City map coloured by measured risk, KPI tiles, zone detail |
| Live Intelligence | Streaming conditions over SSE, per-zone readings and history |
| AI Insights | Correlations, City Memory, and what the platform declines to say |
| Anomaly Detection | Departures from what a zone normally does at this hour |
| Forecast Engine | Five horizons per zone, with the model's measured error |
| What-If Simulator | Scenarios run against real observed conditions |
| Alerts | Automatically raised alerts with the measurement behind each |
| Action Center | Response plans: the step between an alert and an action |
| Impact | What actually followed a recorded action, against the zone's baseline |
| City Analytics | Curated series over 1h–30d, with the coverage behind each point |
| Data Sources | Feed inventory and which ones have gone silent |
| Data Health | What the pipeline received against what it kept |
| API Management | Scoped API keys, shown once and stored hashed |
| Settings | Profile and session management |
| Digital Twin | *not built* |

### Verified, not asserted

| Suite | Count |
|---|---|
| Backend unit (JUnit, surefire) | 87 |
| Backend integration (JUnit, failsafe, real PostgreSQL) | 157 |
| Data platform (pytest) | 223 |
| dbt models and tests | 108 |
| Frontend (Vitest) | 73 |
| **Total** | **648** |

Plus two end-to-end shell suites under [`docs/verification/`](docs/verification/)
that drive the running stack over HTTP.

---

## The principle the code is built around

**The platform may only say things it can point at data for.**

That sounds like a slogan; in practice it decided a great many things:

- An unmeasured value is `null`, never `0`. A dead traffic feed must not render
  as an empty road, and the UI says *"Not measured"* rather than showing a
  zero-valued tile.
- Forecast confidence is computed from the model's error on held-out data. A
  six-hour forecast is less confident than a fifteen-minute one because it was
  *measured* to be worse — the number is read from a database row, not chosen.
- Every alert and anomaly stores the observation, the baseline, and the gap.
  "17,800 against a normal of 8,000" is the whole explanation, and it is written
  at detection time so it stays true as the code changes.
- Correlations ship with `impliesCausation: false` in the payload, so a client
  cannot present co-occurrence as cause by omission.
- City Memory reports what *actually followed* a situation. When too few
  comparable situations exist it says so rather than producing a median over
  three examples.
- Simulated output is labelled as simulated, and the engine refuses to run
  without a real observed baseline — a before/after where the "before" was
  invented is worse than no answer.
- Synthetic data is labelled as synthetic all the way to the browser.

---

## Running it

### What you need

- Java 21, Node 20+, Python 3.12, PostgreSQL 17
- Roughly 2 GB of disk for dependencies

### Setup

```bash
cp .env.example .env
```

Fill in the required values — `.env.example` gives the command to generate each:

```
CITYPULSE_DB_PASSWORD      openssl rand -base64 24
CITYPULSE_JWT_SECRET       openssl rand -base64 48
POSTGRES_PASSWORD          (same as CITYPULSE_DB_PASSWORD)
```

Create the databases:

```bash
createdb citypulse
createdb citypulse_test      # the integration suite runs against real PostgreSQL
```

### Start

```bash
# backend — runs Flyway migrations on boot
cd backend && set -a && . ../.env && set +a && ./mvnw spring-boot:run

# frontend
cd frontend && npm install && npm run dev
```

Open <http://localhost:3000>. API docs at <http://localhost:8080/swagger-ui.html>.

Setting `CITYPULSE_BOOTSTRAP_ADMIN_EMAIL` and `CITYPULSE_BOOTSTRAP_ADMIN_PASSWORD`
creates a first administrator on startup. Leaving them blank creates nothing —
the platform ships with no default account.

### Give it data

Everything above renders empty until telemetry exists. The generator produces
it, labelled as synthetic:

```bash
cd data-engineering
python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt
set -a && . ../.env && set +a

# four weeks of history, then load it
.venv/bin/python -m generator.main --sink jsonl --out /tmp/hist.jsonl --no-realtime \
    --simulate-from "$(date -u -v-28d +%Y-%m-%dT00:00:00Z)" \
    --simulate-to "$(date -u +%Y-%m-%dT%H:%M:%SZ)" --tick-seconds 300
.venv/bin/python -m pipeline.local_runner --input /tmp/hist.jsonl --max-lateness-hours 800

# then the derived layers
.venv/bin/python -m ml.train --model-version v1     # forecasts
.venv/bin/python -m ml.predict
.venv/bin/python -m intelligence.jobs all           # baselines, anomalies, memory
cd dbt && DBT_PROFILES_DIR=. ../.venv/bin/dbt build # analytics marts
```

Everything above is history: correct, but it stops at the moment you loaded it,
and the dashboard says so — an unmeasured present renders as *"Not measured"*,
not as a calm city. To keep the present moving, run the live loop:

```bash
bash data-engineering/scripts/live_loop.sh
```

Each cycle reads the curated watermark, generates exactly the events up to the
last *completed* five-minute window, and loads them. Ranges never overlap, so no
raw event is written twice and no window is built from a partial batch. It ticks
at the same rate as the backfill above, deliberately: a window's `vehicle_count`
is a **sum** over the readings inside it, so a denser live rate would inflate
every window against a baseline learned at the old one, and the detector would
report a spike that belongs to the generator rather than the city.

---

## How it fits together

```
generator ──► Kafka ──► Spark Structured Streaming ──┬──► PostgreSQL (curated)
                                                     └──► MinIO (raw Parquet)
                                                              │
PostgreSQL ──► dbt ──► analytics marts                        │
     │                                                        │
     ├──► ml.train / ml.predict ──► forecasts                 │
     ├──► intelligence.jobs ──► baselines, anomalies, memory  │
     │                                                        │
     └──► Spring Boot API ──► Next.js
```

The pipeline also runs without Kafka or Spark: `pipeline.local_runner` drives the
same validation, windowing and load path directly, and its output was diffed
row-by-row against the Spark job's to prove they agree.

**Validation, derivation and windowing live in `data-engineering/common/`** as
plain Python with no Spark or Kafka imports. Both execution paths import those
modules, so they cannot disagree about what a valid record is or what a window
contains.

### Repository layout

```
backend/            Spring Boot 3.5, Java 21 — auth, RBAC, telemetry, alerts,
                    forecasts, simulation, intelligence
frontend/           Next.js 16, TypeScript, Tailwind
data-engineering/
  common/           Shared contracts, validation, transforms — the single source
  generator/        Synthetic city with correlated urban patterns
  pipeline/         Local runner, Spark job, loader, lake layout
  ml/               Feature engineering, ridge baseline, scoring
  intelligence/     Anomaly detection, baselines, City Memory, correlations
  dbt/              Staging, intermediate and mart models
docs/               Architecture, security, ML, the plan, verification scripts
```

---

## Documentation

| Document | What it covers |
|---|---|
| [PRD.md](docs/PRD.md) | The product requirements this is built to |
| [DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) | Phase status, what was verified, what was not |
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Structure and the decisions behind it |
| [SECURITY.md](docs/SECURITY.md) | Auth model, threat handling, what is deliberately absent |
| [ML.md](docs/ML.md) | Forecasting method and measured error |
| [API.md](docs/API.md) | Endpoint reference |
| [DATA_PIPELINE.md](docs/DATA_PIPELINE.md) | Ingestion, validation and the dead-letter queue |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Running locally and in containers |
| [CONTRIBUTING.md](docs/CONTRIBUTING.md) | Conventions this codebase follows |

---

## What this is not

Stated here rather than left to be discovered:

- **The data is synthetic.** The generator models Indian metro commute patterns
  with coupled weather, incidents and events, which makes the problem realistic
  but not real. The evaluation machinery — temporal holdouts, leakage rules,
  persistence baselines, production scoring — is genuine and would apply
  unchanged to a real feed. The accuracy figures are not a claim about any real
  city.
- **The full container stack has never been started all at once.** Kafka, MinIO
  and Spark have each been run and verified individually; bringing all nine
  compose services up together needs more disk than the development machine had.
  Recorded in the plan rather than ticked quietly.
- **There is no browser-level end-to-end coverage.** "The dashboard updates
  without a refresh" is proven at two levels — the server pushes without being
  asked, and the hook consuming those pushes behaves correctly under drop and
  reconnect — but not by driving a real browser.
- **Two of the five forecast targets are deliberately absent.** Crowd intensity
  has no sensor, and air quality is measured too sparsely at this grain to
  forecast honestly. Both are explained in [ML.md](docs/ML.md) §1.
- **Nothing is deployed.** Phase 8 covers CI, container builds and cloud
  deployment, and has not been done.

---

## Licence

Not yet licensed.
