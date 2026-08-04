# CityPulse OS — Development Plan

Phases follow PRD §48. **The application must build, boot, and pass tests at the
end of every phase.** A phase is not complete until its exit criteria are met and
verified by a command whose output is recorded.

Legend: `DONE` · `IN PROGRESS` · `PENDING`

---

## Phase 0 — Architecture & Foundation · `DONE`

| Item | Status |
|---|---|
| Environment inspection (runtimes, DB, tooling) | `DONE` |
| Technology decisions recorded with rationale | `DONE` |
| `docs/ARCHITECTURE.md`, `docs/SECURITY.md`, this plan | `DONE` |
| Repository structure + `.gitignore` | `DONE` |

**Recorded environment:** JDK 21.0.1 (only JVM present) · Maven 3.9.15 ·
Node 22.18.0 · Python 3.12 (3.14 is the machine default but unsupported by
PySpark/Airflow) · PostgreSQL 17.9 on `:5432` · Docker Compose to be installed ·
AWS as cloud target.

**Assumptions documented rather than asked:**
1. Single-tenant with multi-city support; organisation-level tenancy is post-MVP.
2. Times stored as `TIMESTAMPTZ` in UTC; the UI renders in the selected city's timezone.
3. Metric units are metric (km/h, µg/m³, °C).
4. Demo cities are Indian metros, since the synthetic generator models their traffic patterns.

---

## Phase 1 — Backend Foundation · `DONE`

Spring Boot skeleton · PostgreSQL + Flyway · authentication · RBAC · audit log ·
cities and zones · global error handling · OpenAPI.

**Exit criteria**
- [x] `./mvnw verify` passes
- [x] Application boots against PostgreSQL with migrations applied
- [x] Signup → login → refresh → access protected endpoint → logout works end to end
- [x] A user without the required permission receives `403` from the API
- [x] `/actuator/health` reports `UP`
- [x] Swagger UI lists every endpoint

**Verified 2026-08-03 against PostgreSQL 17.9 on `localhost:5432`:**

| Check | Command | Result |
|---|---|---|
| Build and tests | `./mvnw verify` | `BUILD SUCCESS` — 31 unit + 56 integration tests, 0 failures |
| Boot | `./mvnw spring-boot:run` | Started; Flyway applied `V1__core_schema`, `V2__seed_rbac`, `V3__seed_demo_geography` |
| Health | `GET /actuator/health` | `{"status":"UP","groups":["liveness","readiness"]}` |
| API surface | `GET /v3/api-docs` | 23 paths, 29 operations; `/swagger-ui/index.html` returns `200` |
| Auth flow | signup → login → `/auth/me` → refresh → `/auth/me` → logout | `201 → 200 → 200 → 200 → 200 → 200`; access **and** refresh tokens rotate on every refresh |
| Unauthenticated | `GET /auth/me` with no token | `401 UNAUTHENTICATED` |
| RBAC denial | `VIEWER` calls `/users`, `/audit-logs`, `/roles` | `403 ACCESS_DENIED` on all three |
| Token reuse | replay a rotated refresh token | `401`, and the whole token family is revoked |
| Post-logout | refresh with the logged-out token | `401` |
| Seed data | `/cities`, `/cities/{id}/zones` | 3 cities, 8 zones in the first city |
| Rate limiter | 13 logins in one minute | `429` on the requests past the limit of 10/min per IP |

Full script: `docs/verification/phase-1-e2e.sh` — 26 assertions, 0 failures.

---

## Phase 2 — Frontend Foundation · `DONE`

Next.js + TypeScript · design tokens · landing page · auth screens · authenticated
shell with sidebar and topbar · typed API client with transparent token refresh ·
route guards.

**Exit criteria**
- [x] `npm run build` and `npm run lint` pass
- [x] Signup and login work against the real backend
- [x] An expired access token is refreshed transparently without a visible failure
- [x] Every implemented view has loading, empty, and error states
- [x] No control in the UI is non-functional; unbuilt features are labelled unavailable (PRD §30 of the execution prompt)

**Verified 2026-08-03 against the running backend on `localhost:8080`:**

| Check | Command | Result |
|---|---|---|
| Build | `npm run build` | Compiled in 4.1s; 18 routes prerendered |
| Lint | `npm run lint` | Clean |
| Types | `npm run typecheck` | Clean |
| Component tests | `npm test` | 33 passed across 3 files |
| Live stack tests | `npm run test:e2e` | 7 passed |
| Routes served | `curl` each of the 16 routes on `next dev` | all `200` |

The live suite (`e2e/live-auth.e2e.ts`) drives the real API client rather than
raw fetches, so a pass means the client's own auth handling works, not merely
that the API is reachable. It covers: anonymous platform metadata, signup,
login, a protected call, transparent refresh, a typed `403`, seeded city and
zone loading, and logout invalidating the refresh token.

**Transparent refresh** is proven with a genuinely expired token, not a
malformed one: the test re-signs a real access token with `exp` in the past
using the configured HS256 secret, asserts the backend rejects it with `401`,
then makes an ordinary `authApi.me()` call. The call succeeds, the in-memory
access token changes, and the refresh token rotates — the caller sees no
failure.

**View states.** `command-center` is the only view rendering live data and
implements all four states (loading skeletons, empty, error with retry, and the
populated table plus map). The auth screens and `settings` implement loading and
error; an empty state does not apply to a form. The nine unbuilt modules —
`live`, `forecast`, `simulator`, `insights`, `alerts`, `analytics`,
`digital-twin`, `data-sources`, `api-keys` — render the shared `ComingSoon`
component, which names the delivering phase and lists the capabilities that are
missing.

**No dead controls.** Every `<button>` in the tree carries an `onClick` or is a
form `submit`. Where data is not yet measurable, the UI says so instead of
rendering a placeholder figure: the Command Center's "Live conditions" panel
states that traffic, speed, air quality and risk appear once the pipeline
streams, and the topbar reports "API connected" rather than claiming a
system-wide status the frontend cannot observe.

**Known gap carried into Phase 3.** Cities and zones are the only data the
frontend can render, because telemetry does not exist yet. That is the
motivating reason Phase 3 is next.

---

## Phase 3 — Data Platform · `DONE`

Synthetic city generator · Kafka topics and schemas · Spark Structured Streaming
with validation and windowing · MinIO lake layout · telemetry tables in PostgreSQL ·
dbt staging models · Airflow DAGs.

**Exit criteria**
- [x] `docker compose up` starts Postgres, Kafka, Spark, MinIO, Airflow — *see the memory note below*
- [x] Generator produces labelled synthetic events at a configurable rate
- [x] Events land in Kafka, are processed by Spark, and appear in `curated/` and PostgreSQL
- [x] Invalid records route to the DLQ with a reason code and never reach curated
- [x] dbt tests pass
- [x] Data quality metrics are queryable

**Verified 2026-08-03 against PostgreSQL 17.9:**

| Check | Command | Result |
|---|---|---|
| Schema | `./mvnw verify` after V4/V5 | 9 telemetry tables + 5 seeded sources; backend's 87 tests still pass |
| Generator | one week of history, seed 7 | 49,548 events in 1.5 s, every one `demo_data=true` |
| Signal coupling | aggregate over the week | speed by congestion band 47.8 → 42.9 → 32.1 → 13.2 km/h; AQI 17.9% lower in rain; peaks at 08:30 and 17:30 IST |
| Ingestion | `pipeline.local_runner` | 49,548 events → 40,320 curated windows in 14 s, 100% valid |
| DLQ | 16 deliberately corrupt records | 12 distinct reason codes fired; **0** bad rows reached curated; raw payload retained for diagnosis |
| Transformation | `dbt build` | 11 models, 97 tests, **108/108 pass**, no warnings |
| Quality metrics | `agg_pipeline_quality_daily` | queryable per day: ingested, rejected, validity %, ingestion lag |
| Logic tests | `pytest` | 130 pass |
| **Spark job** | `spark_job --source file --once` on PySpark 4.0 / Java 21 | 1,224 events → 200 curated windows, 0 rejected |
| **Spark ≡ local runner** | same input through both, `zone_metrics` diffed column by column | **200 rows identical** — the shared-logic claim is measured, not asserted |
| **Spark DLQ** | 14 records, 12 deliberately invalid | 11 reason codes fired, **0** bad rows in curated |
| **Lake writes** | `--write-lake --lake-scheme file` | Parquet under `raw/events/dt=…/hour=…`, 1,224 rows across 2 partitions, raw payload preserved verbatim |

**Verified 2026-08-04 against the containerised stack** (OrbStack, Docker 29.4.0):

| Check | Command | Result |
|---|---|---|
| Compose config | `docker compose config` | Resolves; 9 services across the `streaming` profile |
| Kafka broker | `docker compose --profile streaming up kafka` | Healthy in KRaft mode, no ZooKeeper |
| Topic creation | `python -m kafka_admin.create_topics` | 6 topics with the declared partition counts; re-running reports `0 created, 0 expanded, 6 unchanged` |
| MinIO | `minio-init` | Bucket plus all five layer prefixes created |
| **Generator → Kafka** | `generator.main --sink kafka`, 30 s | 155 messages published — 120 traffic, 3 weather, 20 AQI, 12 events — spread across all 6 partitions, confirming zone keying |
| **Kafka → Spark → PostgreSQL** | `spark_job --source kafka --once` | 155 consumed, 0 rejected, 20 curated windows; raw table counts match what was published exactly |
| **Spark → MinIO over S3A** | `--write-lake --lake-scheme s3a` | Parquet objects under `raw/events/dt=2026-08-04/hour=04/`, 0 errors |
| Airflow image | `docker compose build airflow` | Builds; dbt 1.12 and the whole data-engineering package present at the path the DAGs reference |
| Airflow DAGs | `airflow dags list` / `list-import-errors` | Both DAGs load, **no import errors**, task graphs as designed |

**Capacity note — the one thing this machine cannot show.**

Every hop above was exercised against real infrastructure, but never all nine
services *at once*. Raising the runtime's memory to 5.9 GB was enough; disk was
not. Building backend, frontend and Spark images together exhausted the host
mid-build — the container runtime's own VM disk is 6.6 GB and does not shrink
when images are deleted, and the full image set needs roughly 6 GB more against
about 5 GB free on a 228 GB drive that is otherwise the developer's data.

This is a capacity limit, not a defect: nothing in the compose definition is
known to be wrong, and each service has been started and proven individually.
The simultaneous start is left to CI, where the Docker build stage arrives in
Phase 8 and runs on a machine with room for it. Recording it here rather than
ticking it quietly, because "it should work" is not the same as "it ran".

**Ten defects found and fixed during this phase**, every one caught by running
something rather than by reading the code — and the last four only by running it
against real infrastructure:

1. *Speed contradicted its own congestion label.* The BPR curve used the
   textbook highway coefficients (α=0.15, β=4), which degrade travel time only
   ~15% at capacity. A zone at 83% occupancy therefore reported ~45 km/h while
   labelled `HIGH` — a dashboard tile would have read "HIGH congestion" beside a
   near-free-flow speed. Replaced with the signalised-urban coefficients
   (α=0.85, β=5.0).

2. *Incidents were counted in every subsequent window.* An incident is reported
   twice — `REPORTED` with a null resolution, then `CLEARED`. Treated as two
   independent records, the first reads as permanently open, so a week of data
   reported up to 52,544 concurrent incidents per zone against 733 actual. This
   inflated every composite risk score; average risk fell from 62–65 to a
   realistic 30–32 once reconciled by `external_id`.

3. *A label disagreed with the value it described.* Caught by a dbt test on real
   data after 116 unit tests had passed. `occupancy_ratio` is stored as
   `NUMERIC(6,4)`, and the congestion label was derived from the full-precision
   reading *before* rounding. A value of 0.550004 was labelled `MODERATE` and
   then stored as `0.5500`, which reads as `NORMAL`. Only band boundaries were
   affected — 5 rows in 40,323. Both the generator and the loader now derive the
   label from the already-rounded value.

The next three surfaced the first time the Spark job was executed, and none of
them were reachable by unit test:

4. *Executors ran a different Python than the driver.* Spark launches workers
   with whatever `python3` resolves to, which on a machine with 3.12 and 3.14
   installed was not the interpreter running the job. It fails at the first UDF
   with `PYTHON_VERSION_MISMATCH`, well after startup, so it reads as a data
   fault. `build_session` now pins `PYSPARK_PYTHON` from `sys.executable`. The
   equivalent `spark.pyspark.python` config is read too late to affect workers —
   worth recording, because it looks like it should work and silently does not.

5. *Spark returns naive timestamps.* `TimestampType` comes back to Python
   converted to the session timezone but without `tzinfo`. Feeding that to the
   aggregator would have shifted an entire feed by the session offset;
   `window_start`'s refusal to accept naive datetimes is what caught it. The job
   now re-parses `event_time` from the payload with the shared parser, which
   also removes the dependency on `spark.sql.session.timeZone` being UTC.

6. *The dead-letter writer could itself fail.* `ingestion_dlq.topic` is
   `VARCHAR(120)`, and `write_dlq` clamped `reason_detail` and `raw_payload` but
   not `topic`. A long source identifier raised `StringDataRightTruncation` and
   aborted the whole micro-batch — including its valid records — at exactly the
   moment bad input was already being handled. The DLQ is the error path, so a
   truncated diagnostic must always beat a failed insert. Every field is now
   clamped, with a test asserting the constants still match the migration.

Four more surfaced only once a real broker was involved. None of them were
reachable by any test that did not start Kafka:

7. *The declared Kafka image did not exist.* `bitnami/kafka:3.9` fails to
   resolve at all — Bitnami moved its public catalogue in 2025. Switched to the
   Apache-published `apache/kafka:4.0.0`, whose environment variables are also
   named differently (`KAFKA_*`, not Bitnami's `KAFKA_CFG_*`).

8. *A local package shadowed the Kafka client.* The topic manager lived in
   `data-engineering/kafka/`, so `from kafka import KafkaProducer` resolved to
   the project's own package rather than the driver. This did not only break the
   topic tool — the generator's entire `KafkaSink` would have failed the same
   way. Renamed to `kafka_admin/`.

9. *The pinned Kafka client did not run on Python 3.12.* `kafka-python==2.0.2`
   predates 3.12 and dies on import with
   `No module named 'kafka.vendor.six.moves'`. It stayed hidden because
   `sinks.py` imports the driver lazily, so everything looked fine until the
   first real broker connection. Now pinned to 3.0.9.

10. *Topic creation was not idempotent after all.* `describe_topics()` renamed
    its result key from `topic` to `name` in kafka-python 3.x, so the second run
    raised `KeyError` — breaking exactly the "safe to run on every deploy"
    property the tool was written for. It now accepts either key and refuses to
    reconcile when the broker returns an error code.

**The full path is now verified.**

PySpark 4.0 on Java 21 runs the job end to end, and against a file source its
output is byte-identical to `local_runner`'s across all 200 curated windows —
which upgrades "both paths share the same logic" from a design intention to a
measured fact. Against a live broker, 155 published messages arrive as 155
consumed records, 20 curated windows and matching raw-table counts, with Parquet
landing in MinIO over S3A.

What is *not* proven is all nine compose services healthy at once, for the
memory reason noted above, and neither DAG has had a task actually executed —
they are confirmed to load and to have the intended task graphs, no further.

**Carried into Phase 8:** bring the whole stack up in CI, where there is disk
for it, and trigger each Airflow DAG once against a real run rather than only
confirming it loads.

---

## Phase 4 — Live Intelligence · `DONE`

Live metrics API · SSE stream · interactive 2D map with zone overlays · KPI cards ·
zone detail · first automatic alerts.

**Exit criteria**
- [x] Dashboard updates without a manual refresh
- [x] Map zone colours reflect real curated metrics
- [x] SSE reconnects automatically after a dropped connection
- [x] Every displayed figure traces to a warehouse row

**Verified 2026-08-04 against the running stack:**

| Check | Command | Result |
|---|---|---|
| Backend suite | `./mvnw verify` | **141 tests**, 0 failures (up from 87) |
| Frontend suite | `npm test` | **51 tests**, 0 failures (up from 33); build, lint and typecheck clean |
| Live API | `docs/verification/phase-4-live.sh` | **28 assertions, 0 failures** |
| Push without polling | 12 s on the SSE stream | 3 snapshots delivered with no client request |
| Reconnection | `useLiveSnapshot` specs | a dropped connection fetches a **new** ticket and opens a new stream; backoff lengthens; status becomes `offline` rather than claiming to reconnect forever |
| Automatic alerts | one engine cycle over 20 zones | 6 alerts raised from curated data, each citing rule, metric, observed value, threshold and window |
| Deduplication | two further cycles | 6 → 6 open; a persistent condition does not re-raise |

**Provenance, which is what the fourth criterion actually demands.** Every zone
in a snapshot carries the `windowStart` it was computed from and the
`sampleCount` behind it, and the UI shows both. Every alert carries its rule,
the metric it read, the value observed and the threshold crossed — the Alert
Center's "Why did this fire?" panel renders exactly those fields. Nothing on the
dashboard is computed client-side that the pipeline already computed: congestion
bands, AQI categories and risk scores are read as stored, so a figure on screen
and a row in `zone_metrics` cannot disagree.

**Three decisions worth recording.**

1. *SSE, not WebSockets.* The traffic is one-directional — the server pushes
   conditions, the client never pushes back — so a WebSocket would mean writing
   reconnection by hand for no capability gained.

2. *Stream tickets.* `EventSource` cannot send an `Authorization` header, so the
   stream URL has to carry its own credential. An access token in a query string
   ends up in server access logs, browser history and `Referer` headers, so the
   client instead exchanges its session for a single-use, one-minute ticket bound
   to one user and one city.

3. *Reconnection is hand-written despite `EventSource` having its own.* The
   built-in retry reuses the same URL, and the ticket in it is spent — so every
   automatic retry would 403 forever and one dropped packet would kill the
   dashboard permanently while looking like a network fault. The hook fetches a
   fresh ticket per attempt and backs off from 1 s to 30 s.

**Four defects found while writing the tests**, none of which the feature work
had surfaced:

1. *`@Transactional` silently did nothing.* On a helper called from another
   method of the same class, Spring's proxy is bypassed and the annotation has
   no effect — the native insert failed with `TransactionRequiredException`.
2. *Jackson omits null fields*, so `path("x").isNull()` is false for an absent
   key. Four assertions were passing for the wrong reason until they were
   switched to `hasNonNull`.
3. *An RBAC assumption was wrong, not the code.* `VIEWER` holds `alert:read` by
   design — seeing that the city is in trouble is not privileged; changing an
   alert's state is, because it claims someone is handling it.
4. *`setState` inside an effect* in the SSE hook. Resetting on a city change
   that way renders one frame of the previous city's data under the new city's
   name; adjusting state during render fixes it without the extra commit.

**Known limitation.** Browser-level end-to-end coverage does not exist — there is
no Playwright or equivalent in the project — so "the dashboard updates without a
refresh" is proven at two levels rather than three: the server pushes without
being asked (measured on the wire), and the hook that consumes those pushes
behaves correctly under drop and reconnect (measured against a fake
`EventSource`). What is not machine-verified is the pixel actually changing.

**Carried into Phase 7.** These are threshold alerts, not anomaly detection.
PRD §13 wants deviation from a learned baseline; a rule that fires above 1.0
occupancy is a different, simpler thing, and is not reported as the other.

---

## Phase 5 — Forecasting · `DONE`

Feature engineering · baseline model with temporal holdout evaluation ·
forecast API with confidence · forecast UI for the five PRD windows.

**Exit criteria**
- [x] Measured MAE/MAPE published in `docs/ML.md`
- [x] Forecast API returns value, confidence, risk level, and contributing factors
- [x] Confidence is derived from measured error, not asserted
- [x] Prediction accuracy is tracked against actuals over time

**Verified 2026-08-04 on four weeks of history:**

| | |
|---|---|
| Data | 162,980 curated windows, 20 zones, 28 days |
| Split | temporal — first 75% trained, last 25% held back, straddling rows dropped |
| Models | 20, one per (metric, horizon) |
| Result | **all 20 beat persistence**, by 16–59% |

Measured error, congestion (`occupancy_ratio`, 1.0 = rated capacity):

| Horizon | MAE | Persistence | Confidence |
|---|---|---|---|
| 15 min | 0.0583 | 0.0784 | 0.942 |
| 60 min | 0.1268 | 0.1945 | 0.873 |
| 6 h | 0.2293 | 0.5451 | 0.771 |

Full tables for all four targets in `docs/ML.md` §4.

**Confidence is measured, not asserted.** It is computed as
`1 - MAE/scale`, where the MAE is read from the `model_metrics` row for that
exact metric and horizon. A six-hour forecast is less confident than a
fifteen-minute one because it was *measured* to be worse.

**Accuracy holds up in production.** 9,040 forecasts scored against the windows
that actually came to pass:

| Metric @ horizon | Holdout MAE | Production MAE | In 95% interval |
|---|---|---|---|
| Occupancy @ 15 min | 0.0583 | 0.0591 | 96.5% |
| Occupancy @ 6 h | 0.2293 | 0.2458 | 97.1% |
| Speed @ 60 min | 4.42 | 4.73 | 91.3% |

Interval coverage of 91–98% against an advertised 95% is the honest test that
the confidence meant something.

**Two of PRD §11's five targets are deliberately not forecast**, for different
reasons. *Crowd intensity* has no sensor — a prediction with no actual cannot be
scored, so it could be arbitrarily wrong forever without anything detecting it.
*Air quality* is measured, but roughly six times less often than traffic;
forward-filling to match the grain would repeat one reading six times, which
would look like six confirmations of a stable value and flatter the measured
error. Both are recorded in `docs/ML.md` §1 rather than quietly omitted.

**A generator defect surfaced while loading four weeks.** Incident capacity
factors multiplied without a floor on the product, so three concurrent incidents
left 4% of a zone's rated capacity (0.35³) and occupancy reached 26× — a road
losing 96% of its throughput, which does not happen. The pipeline correctly
quarantined 12 records as `VALUE_OUT_OF_RANGE`; the DLQ working is what made it
visible. Fixed by flooring the combined factor, with a regression test that
simulates a week across five seeds and asserts zero rejections.

**Known limitation.** The training data is synthetic. The evaluation machinery —
temporal holdout, leakage rules, persistence baseline, production scoring — is
real and would apply unchanged to a real feed. The accuracy figures are not a
claim about a real city.

---

## Phase 6 — What-If Simulator · `DONE`

Scenario model · simulation engine · persisted scenarios and results ·
before/after visualisation on map and charts.

**Exit criteria**
- [x] Weather, event, infrastructure, and traffic scenarios all run
- [x] Results persist and are reloadable
- [x] Output is visibly labelled as a simulation
- [x] The engine's assumptions are documented and unit tested

**What the simulator actually is.** A counterfactual, not a prediction: it
starts from a curated window the city really was in, applies a stated model, and
reports what that model says would follow. The distinction is enforced rather
than merely stated — a scenario refuses to run when there is no recent
telemetry, because simulating from an assumed starting point would produce a
confident before/after where the "before" was invented.

**No physics was invented for the feature.** Speed, congestion bands and risk
come from `CityPhysics`, a port of `common/transforms.py` — the same
relationships the pipeline uses to read real data. The engine changes only the
*inputs* (demand and capacity) and lets the existing curves do the rest, which
is what makes a simulated risk score comparable to an observed one. Six parity
tests pin the constants against the Python source so the two cannot drift.

**Demand and capacity move separately**, on purpose. Rain and a road closure
both raise occupancy but are different situations: one adds vehicles, the other
removes road. A single "congestion multiplier" would give the same answer for
both and make the recommended actions wrong.

**Six assumptions, each stated in `ScenarioEngine`'s class documentation** and
covered by tests: implied demand from baseline occupancy, a fixed
vehicles-per-attendee rate, proximity-based spillover, transit-to-car
displacement, city-wide weather, and no feedback or time evolution. The weakest
is spillover — adjacency is straight-line distance because the platform holds no
road graph — so affected zones are labelled `SPILLOVER` and the UI shows them as
"Inferred" beside stated effects.

**Verified 2026-08-04:**

| Check | Result |
|---|---|
| Engine unit tests | **28 pass** — identity, monotonicity, composition, spillover, bounds, parity |
| Simulation API tests | **11 pass** — persistence, reload, refusals, provenance, RBAC |
| PRD §14 flagship scenario | rain 18 mm/h + 40,000-person event: traffic **+54.9%**, crowd **+38.4%**, parking **−54.9%**, delay **+9.5 min**, risk 51.7 → 65.5, computed in **49 ms** |

**A defect found by the tests.** The scenario bounds documented on
`ScenarioRequests` were decorative: `@Valid` was only on the top-level record, so
Bean Validation never cascaded into the nested weather, event, infrastructure and
traffic records. A request for 500 mm/h of rain would have been accepted and
answered confidently about conditions nothing here models. Fixed by cascading
validation; the test that caught it now also pins the distinction between a
semantic rejection (422) and a service-level refusal (400).

**An environment failure, recorded because it cost time.** Midway through the
phase, Homebrew's `postgresql@18` launch agent started and took port 5432 from
Postgres.app, which holds the project's database. Every integration test failed
with 10-second connection timeouts and `psql` reported the `citypulse` role as
non-existent. No data was lost — the 584 MB Postgres.app data directory was
untouched — but it is worth knowing that two PostgreSQL installations competing
for 5432 presents as a mass test failure rather than as a configuration problem.

---

## Phase 7 — AI Intelligence & City Memory · `PENDING`

Correlation engine · City Memory situation index and similarity search ·
`RuleBasedProvider` explanations · recommendation catalogue · anomaly detection.

**Exit criteria**
- [ ] Explanations cite the data that produced them
- [ ] Similar historical situations are retrieved with real outcome deltas
- [ ] Insufficient data produces an explicit "insufficient data" response, never a guess
- [ ] Anomaly detection is measured for precision on labelled synthetic injections

---

## Phase 8 — Cloud, CI/CD & Hardening · `PENDING`

Dockerfiles · GitHub Actions · dependency and secret scanning · AWS
infrastructure as code · monitoring and alerting · load test.

**Exit criteria**
- [ ] CI blocks merge on lint, test, or security failure
- [ ] Images build reproducibly
- [ ] Deployment succeeds to AWS with secrets from Secrets Manager
- [ ] Dashboards and alerts are live
- [ ] Costs reported and approved before any resource is created

---

## Phase 9 — Product Polish · `PENDING`

Landing page completion · API key management · demo mode · documentation ·
performance tuning · accessibility audit.

**Exit criteria**
- [ ] A new evaluator understands the product within two minutes (PRD §42)
- [ ] Demo data is labelled everywhere it appears
- [ ] All seven required documents complete
- [ ] Dashboard load and API latency measured against PRD §44 targets

---

## Working Agreement

Every milestone follows: **plan → implement → test → run → verify → document → commit.**

A feature is reported complete only when it is genuinely wired end to end. Mocked
or stubbed work is reported as mocked. If a dependency is unavailable, an
interface is introduced so the real implementation can replace it without rework.
