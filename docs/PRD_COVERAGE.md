# PRD Coverage

Section-by-section status of `docs/PRD.md` against what is actually in the
repository. Assessed 2026-08-03 against a running stack (backend on `:8080`,
frontend on `:3000`, PostgreSQL 17.9).

A section is `DONE` only when it is wired end to end and verified. Anything
mocked, stubbed or merely routed is `PARTIAL` or `NOT STARTED`, per PRD §49.11.

| | Count |
|---|---|
| ✅ DONE | 9 / 50 |
| 🟡 PARTIAL | 22 / 50 |
| ⬜ NOT STARTED | 19 / 50 |

---

## Summary by section

| § | Requirement | Status | Evidence / gap |
|---|---|---|---|
| 1 | Product overview | 🟡 | Vision stated on the landing page; the intelligence loop itself is unbuilt |
| 2 | Core problem | ✅ | Articulated in the landing page's "Analysed independently → Correlated" section |
| 3 | Product objectives | ⬜ | 0 of 8 delivered (real-time, predictive, correlation, anomaly, what-if, recommendations, developer platform); only "enterprise architecture" is partly in place |
| 4 | Target users | 🟡 | All 5 personas have roles; only administrator and viewer capabilities actually function |
| 5 | User roles (RBAC) | ✅ | 7 roles + 23 permissions seeded in `V2__seed_rbac.sql`; backend enforcement verified — `403 ACCESS_DENIED` on `/users`, `/audit-logs`, `/roles` |
| 6.1 | Landing page | 🟡 | 7 of 13 sections: hero, problem, how it works, capabilities, security, CTA, footer. Missing: live preview, simulation preview, analytics, API platform, pricing, documentation |
| 7 | Authentication | ✅ | Signup, login, logout, refresh, forgot, reset, verify-email, sessions. BCrypt hashing, JWT HS256 (15 min), rotating refresh tokens with reuse detection — all verified |
| 8 | Command Center | 🟡 | Shell, sidebar, city selector, interactive map, zone detail ✅. **KPI cards: 0 of 8** — no congestion, speed, incidents, crowd, AQI, weather, energy or alerts. "Reports" and notifications missing from nav |
| 9 | Live Intelligence | ⬜ | Route renders `ComingSoon`. No SSE/WebSocket anywhere in the codebase |
| 10 | City Digital Twin | 🟡 | 2D Leaflet map with clickable zones exists in the Command Center; the dedicated module is `ComingSoon` and zones carry no traffic/crowd/AQI/risk state |
| 11 | Forecast Engine | ⬜ | `ComingSoon` |
| 12 | Event Correlation | ⬜ | Not started — a core differentiator |
| 13 | Anomaly Detection | ⬜ | Not started |
| 14 | What-If Simulator | ⬜ | `ComingSoon` — flagship feature |
| 15 | AI Intelligence Panel | ⬜ | Not started |
| 16 | City Memory | ⬜ | Not started |
| 17 | Alert Center | ⬜ | `ComingSoon` |
| 18 | Analytics | ⬜ | `ComingSoon`; no charting library installed |
| 19 | Data engineering platform | ⬜ | `data-engineering/` is empty |
| 20 | Data ingestion (Kafka) | ⬜ | Not in `docker-compose.yml` |
| 21 | Stream processing (Spark) | ⬜ | Not started |
| 22 | Data lake | ⬜ | No MinIO/S3; no `raw/processed/curated/features` layout |
| 23 | Data warehouse | 🟡 | **6 of 21** suggested tables: `users`, `roles`, `permissions`, `cities`, `zones`, `audit_logs`. Missing all 15 telemetry/intelligence tables (`traffic_events`, `forecasts`, `anomalies`, `alerts`, `simulations`, `api_keys`, …) |
| 24 | dbt transformation | ⬜ | Not started |
| 25 | Airflow orchestration | ⬜ | Not started |
| 26 | Backend | 🟡 | Java 21 + Spring Boot, layered Controller→Service→Repository, DTOs, no entity leakage ✅. **6 of 12 responsibilities**: auth, authz, users, cities, audit ✅; traffic, forecast, alert, simulation, analytics, API keys, SSE ⬜ |
| 27 | API design | 🟡 | **4 of 12** namespaces (`auth`, `users`, `cities`, `zones`). Cross-cutting rules all met: validation, status codes, consistent errors, authn/authz, logging, rate limiting |
| 28 | API response format | ✅ | `{success, data, message}` / `{success, error:{code, message, requestId, timestamp}}`; no stack traces leak — verified |
| 29 | API platform | ⬜ | `ComingSoon`; no `api_keys` table |
| 30 | Security | ✅ | BCrypt, JWT expiry, RBAC, rate limiting (10/min per IP, verified firing), input validation, 16KB header cap, CSP + HSTS + frame-deny, CORS allowlist, JPA parameterised queries, env-only secrets, audit log on login/logout/password/role changes |
| 31 | Frontend technology | 🟡 | Next.js 16 + TypeScript + Tailwind v4 + Leaflet, responsive, accessible, all four states ✅. **No charting library, no SSE client** |
| 32 | Design system | ✅ | Dark enterprise token layer; no gradients, glow or cyberpunk styling |
| 33 | DevOps | 🟡 | Frontend + backend Dockerfiles and a compose file with `postgres`/`backend`/`frontend`. No data-service containers |
| 34 | CI/CD | 🟡 | GitHub Actions with 3 jobs (backend build+test, frontend lint/typecheck/test/build, security). **No Docker build or deploy stage** |
| 35 | Observability | 🟡 | Structured logs with request IDs ✅; `/actuator/health` with liveness/readiness ✅; `prometheus`/`metrics` exposed but no custom metrics tracked; no alerting |
| 36 | Testing | 🟡 | Backend 87 tests (unit, integration, API, security) ✅; frontend 40 tests incl. live-stack flows ✅. **No data quality tests**; the e2e chain stops at "view city" — live data, forecast and simulation steps do not exist |
| 37 | Documentation | 🟡 | **2 of 7**: `ARCHITECTURE.md`, `SECURITY.md` (plus `PRD.md`, `DEVELOPMENT_PLAN.md`, this file). Missing `README.md`, `API.md`, `DATA_PIPELINE.md`, `DEPLOYMENT.md`, `CONTRIBUTING.md` |
| 38 | Project structure | 🟡 | All top-level directories exist, but `data-engineering/`, `ml/`, `infrastructure/`, `monitoring/`, `scripts/`, `tests/` contain **zero files**, and the `kafka/spark/airflow/dbt` subdirectories are absent |
| 39 | Development principles | ✅ | Incremental phases, runnable at each milestone, no fake functionality, no hardcoded data, env-based config, no committed secrets, tests on business logic, decisions documented |
| 40 | MVP scope | 🟡 | Auth ✅, frontend UI ✅; Command Center partial; data pipeline, intelligence, simulator and alerts not started |
| 41 | Post-MVP features | ✅ | Correctly deferred — none started ahead of the core |
| 42 | Demo mode | 🟡 | `demoData` flag runs end to end and `DemoDataBadge` labels cities and zones. Only geography is demoable, so the 2-minute comprehension goal is not met yet |
| 43 | Data source strategy | ⬜ | No real or synthetic sources exist |
| 44 | Performance | 🟡 | DB indexes ✅, pagination via `PageResponse` ✅. No caching layer; no measured latency targets |
| 45 | Scalability | 🟡 | Modular monolith and multi-city schema support the 1→10→100 path by design; untested at any scale |
| 46 | Product differentiators | ⬜ | **0 of 6** — correlation, City Memory, simulation, AI explanations, recommendations, API-first consumption |
| 47 | Success criteria | 🟡 | **6 of 20** fully met (see below) |
| 48 | Implementation order | 🟡 | Phases 0, 1, 2 complete and verified; **3 of 10** |
| 49 | Agent instructions | ✅ | Analysis, architecture, schema, plan and phased implementation all followed; nothing reported complete that is only mocked |
| 50 | Final product definition | 🟡 | Product, frontend, backend and security engineering demonstrated. Data engineering, streaming, big data, AI/ML, cloud and observability not yet |

---

## §47 Success criteria — 6 of 20

| # | Criterion | |
|---|---|---|
| 1 | Account creation and secure login | ✅ |
| 2 | View a city dashboard | ✅ |
| 3 | Events flow through the data pipeline | ⬜ |
| 4 | Kafka receives streaming events | ⬜ |
| 5 | Spark processes the events | ⬜ |
| 6 | Processed data reaches storage | ⬜ |
| 7 | Dashboard displays processed data | 🟡 geography only |
| 8 | System detects anomalies | ⬜ |
| 9 | System generates forecasts | ⬜ |
| 10 | User can create a what-if scenario | ⬜ |
| 11 | System generates simulation results | ⬜ |
| 12 | Alerts are generated | ⬜ |
| 13 | Backend APIs are secured | ✅ |
| 14 | RBAC works correctly | ✅ |
| 15 | Deployed to the cloud | ⬜ |
| 16 | CI/CD works | 🟡 no deploy stage |
| 17 | Logs and health checks work | ✅ |
| 18 | Documentation available | 🟡 2 of 7 |
| 19 | No secrets committed | ✅ |
| 20 | Demonstrable end to end | ⬜ |

---

## Honest overall position

Phases 0–2 are complete and verified. Measured against the PRD as a whole, that
is roughly **25% of the product** — the phase count (3 of 10) reads higher than
the real position because the foundation phases are the smaller ones, and every
remaining phase carries the features the PRD calls differentiating.

What exists is sound rather than broad: no mocked data, no non-functional
controls, every figure traced to a database row, and the security requirements
of §30 met in full.

The single largest gap is **§19–25, the data platform**. Nothing in §9–18 —
live intelligence, forecasting, correlation, anomalies, simulation, City Memory,
alerts or analytics — can be built before telemetry exists, and none of those
tables are in the schema yet. Phase 3 is therefore the only sensible next step,
and it unblocks the majority of the remaining PRD.

### Cheapest gaps to close outside Phase 3

These do not depend on the pipeline and could be done at any time:

- `README.md`, `API.md`, `DEPLOYMENT.md`, `CONTRIBUTING.md` (§37) — four missing documents
- Docker build and deploy stages in CI (§34)
- Landing page: pricing, documentation and API-platform sections (§6.1)
- "Reports" entry and notifications in the Command Center nav (§8)
