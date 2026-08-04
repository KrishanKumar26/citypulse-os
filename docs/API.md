# CityPulse OS — API Reference

Interactive documentation, generated from the code and always current, is at
<http://localhost:8080/swagger-ui.html> when the backend is running. This file
covers the conventions that apply everywhere and the reasoning behind the parts
that are unusual.

---

## 1. Conventions

### Envelope

Every response — success or failure — has the same shape, so a client never has
to guess which it received:

```json
{
  "success": true,
  "data": { },
  "message": "Optional human-readable note",
  "requestId": "554fe342-99a6-49a5-8330-c61bce439600",
  "timestamp": "2026-08-04T11:38:20.027418Z"
}
```

```json
{
  "success": false,
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "City 'atlantis' was not found",
    "fieldErrors": [
      { "field": "email", "message": "Email is required" }
    ]
  },
  "requestId": "554fe342-99a6-49a5-8330-c61bce439600",
  "timestamp": "2026-08-04T11:38:20.027418Z"
}
```

The `requestId` appears in the response *and* in the server log line for that
request, so a user reporting a failure can be matched to the exact log entry
without guessing from timestamps.

### Status codes

| Code | Meaning |
|---|---|
| 200 / 201 | Success |
| 400 | Well-formed but semantically refused by business logic |
| 401 | No credentials, or they are invalid |
| 403 | Authenticated but not permitted |
| 404 | Not found, or the caller may not know it exists |
| 409 | Conflicts with current state |
| 422 | Bean-validation failure — syntactically fine, out of bounds |
| 429 | Rate limited |

**400 and 422 are distinguished on purpose.** A 422 means a field violated a
declared constraint; a 400 means the request was valid but the operation was
refused — an empty scenario, a horizon with no trained model. A client can
usefully retry differently in each case.

### Identifiers

Resources are addressed by an opaque `uid` (UUID), never by the internal
sequential id. Sequential ids in URLs let a caller enumerate resources and infer
record counts.

### Nulls

**A `null` means not measured. It never means zero.** Unmeasured metrics are
omitted from JSON entirely (Jackson drops nulls), so a client should test for
absence rather than for a falsy value. A dashboard that renders a missing
traffic reading as `0` reports a dead feed as an empty road.

### Pagination

Standard Spring `page`, `size` and `sort` parameters. Paged responses:

```json
{
  "items": [ ],
  "page": 0,
  "size": 20,
  "totalElements": 143,
  "totalPages": 8,
  "first": true,
  "last": false
}
```

---

## 2. Authentication

Bearer tokens. `Authorization: Bearer <accessToken>` on every request except the
public endpoints below.

- **Access token** — 15 minutes, stateless JWT.
- **Refresh token** — 7 days, stored hashed, **rotated on every use**.

**Refresh token reuse is treated as theft.** Presenting a token that has already
been exchanged revokes the entire token family, including the legitimate
holder's current token. That is deliberate: an attacker with a stolen token and
the real user both get logged out, which is the safe direction.

### Public endpoints

```
POST /api/v1/auth/signup            POST /api/v1/auth/login
POST /api/v1/auth/refresh           POST /api/v1/auth/logout
POST /api/v1/auth/forgot-password   POST /api/v1/auth/reset-password
GET  /api/v1/auth/verify-email      GET  /api/v1/meta/platform
GET  /actuator/health
```

`GET /api/v1/live/by-slug/{slug}/stream` is also outside the bearer chain — but
it is *not* public. See §5.

---

## 3. Permissions

Seven roles, seeded by migration V2. Endpoints require a permission, not a role,
so a role can be re-scoped without touching code.

| Permission | Grants |
|---|---|
| `city:read` `city:write` | Geography |
| `zone:read` `zone:write` | Zones |
| `telemetry:read` | Live conditions and history |
| `forecast:read` | Predictions and model accuracy |
| `alert:read` `alert:manage` | Reading and working alerts |
| `anomaly:read` | Anomalies |
| `analytics:read` | Correlations, City Memory, insights |
| `simulation:read` `simulation:create` | Scenarios |
| `user:read` `user:write` `user:manage_roles` | User administration |
| `audit:read` `system:manage` | Platform administration |

A `VIEWER` holds `alert:read` but not `alert:manage`: knowing the city is in
trouble is not privileged, while changing an alert's state is — it claims
someone is handling it.

---

## 4. Endpoints

### Authentication — `/api/v1/auth`

| | |
|---|---|
| `POST /signup` | Create an account. Assigned `VIEWER` until an admin elevates it. |
| `POST /login` | Returns an access and refresh token pair |
| `POST /refresh` | Exchange a refresh token; the old one is consumed |
| `POST /logout` | Revoke the presented refresh token |
| `POST /forgot-password` · `POST /reset-password` | Reset flow |
| `GET /verify-email` | Verify an address |
| `POST /change-password` | Requires the current password |
| `GET /me` | The authenticated user's profile, roles and permissions |

### Geography — `/api/v1/cities`, `/api/v1/zones`

| | |
|---|---|
| `GET /cities` · `GET /cities/{cityId}` · `GET /cities/by-slug/{slug}` | Read |
| `POST /cities` · `PUT /cities/{cityId}` · `DELETE /cities/{cityId}` | Write; delete is a soft delete |
| `GET /cities/{cityId}/zones` · `GET /cities/{cityId}/zones/search` | List and search |
| `POST /cities/{cityId}/zones` | Create — a zone only exists within a city |
| `GET /zones/{zoneId}` · `PUT` · `DELETE` | By zone identifier |
| `GET /zones/{zoneId}/boundary` | Polygon geometry, served separately so list responses stay small |

### Live conditions — `/api/v1/live`

| | |
|---|---|
| `GET /by-slug/{slug}` | One consistent snapshot: KPIs, every zone, freshness |
| `GET /cities/{cityId}` | The same, by identifier |
| `GET /zones/{zoneId}/history` | A zone's recent windows for trend charts |
| `POST /by-slug/{slug}/stream-ticket` | A single-use ticket for the stream |
| `GET /by-slug/{slug}/stream` | Server-sent events (see §5) |

The snapshot is one payload rather than several endpoints because the map, the
KPI row and the staleness banner must agree with each other. Fetched separately
they would drift by a window.

Each zone carries `hasData`, `windowStart` and `sampleCount`. A window built
from two readings is not as trustworthy as one from sixty, and a client can only
qualify a thin sample if it can see the count.

### Forecasts — `/api/v1/forecasts`

| | |
|---|---|
| `GET /zones/{zoneId}?metric=` | All five horizons, with intervals and factors |
| `GET /cities/{slug}?metric=&horizonMinutes=` | Every zone at one horizon |
| `GET /accuracy` | Production error beside holdout error |

Every forecast carries `measuredMae` and `baselineMae`. Confidence is derived
from the first; the second is the naive "nothing changes" prediction, reported
because a model that cannot beat it has not earned its complexity.

Supported metrics: `occupancy_ratio`, `average_speed_kph`, `vehicle_count`,
`risk_score`. Requesting `crowd_intensity` or `aqi` returns 400 with the reason —
see [ML.md](ML.md) §1.

### Alerts — `/api/v1/alerts`

| | |
|---|---|
| `GET /` | Open alerts by default, most severe first |
| `GET /summary` | Counts by severity, for the badge |
| `GET /{alertId}` | One alert with its full provenance |
| `PATCH /{alertId}/status` | Acknowledge, investigate or resolve |

A resolved alert cannot be reopened — that would make the recorded resolution
time false. A recurrence raises a new alert with its own timeline.

### Intelligence — `/api/v1/anomalies`

| | |
|---|---|
| `GET /?citySlug=&severity=&hours=` | Anomalies, furthest from normal first |
| `GET /correlations?citySlug=` | Measured co-occurrence |
| `GET /memory?citySlug=&rainBand=&hourBand=&dayType=&hadEvent=&incidentBand=` | What historically followed |
| `GET /insights?citySlug=` | All of the above plus the current situation |

Correlations carry `impliesCausation: false`. Memory recalls carry
`sufficientData` — when false, the aggregate figures are null and
`insufficientReason` explains why. That is not an error state.

### Simulations — `/api/v1/simulations`

| | |
|---|---|
| `POST /` | Run a scenario; computed synchronously |
| `GET /{simulationId}` | Reload a saved scenario |
| `GET /?citySlug=` | Scenario history |

Every result carries `baselineWindow` and `engineVersion`. Without the first,
"traffic +43%" is a percentage of nothing in particular once conditions move on;
without the second, a result read later cannot say which assumptions produced it.

### Administration

| | |
|---|---|
| `GET /api/v1/users` · `GET /{userId}` | User directory |
| `PUT /api/v1/users/{userId}/roles` | Replace roles |
| `PATCH /api/v1/users/{userId}/status` | Activate or suspend |
| `GET /api/v1/roles` | Role catalogue |
| `GET /api/v1/audit-logs` | Audit trail |
| `GET /api/v1/meta/platform` | Version, demo-mode flag, mail availability |

---

## 5. The live stream

`GET /api/v1/live/by-slug/{slug}/stream` is server-sent events, and it
authenticates differently from every other endpoint.

**Why.** The browser's `EventSource` API cannot set request headers, so the URL
has to carry its own credential. Putting the access token in the query string
would be worse than it looks: query strings land in web server access logs,
browser history, and `Referer` headers sent to any third-party resource the page
loads. A 15-minute token in all three places is a real leak.

**Instead:**

```
POST /api/v1/live/by-slug/bengaluru/stream-ticket    (with Bearer)
  → { "ticket": "…", "expiresInSeconds": 60 }

GET  /api/v1/live/by-slug/bengaluru/stream?ticket=…
```

The ticket is opaque, valid for one minute, usable exactly once, and bound to
both the user and the city. Even captured from a log it is almost certainly
already spent.

### Events

```
event: snapshot     the same payload as GET /by-slug/{slug}, on every push cycle
event: heartbeat    periodic, so an idle connection is distinguishable from a dead one
```

### Reconnection

The stream advertises a retry interval, but **a client must not rely on
`EventSource`'s built-in reconnect**: it retries the same URL, and the ticket in
it is spent — every automatic retry would 403 forever, so one dropped packet
would appear to kill the stream permanently. Fetch a fresh ticket and open a new
stream. The reference implementation is
`frontend/src/lib/live/useLiveSnapshot.ts`, which backs off from 1 s to 30 s.

---

## 6. Rate limiting

Authentication endpoints are rate limited per IP. Exceeding it returns 429 with
a `Retry-After` header. Failed logins also count toward per-account lockout —
see [SECURITY.md](SECURITY.md).

---

## 7. Demo data

Every payload derived from telemetry carries `demoData`. While the platform runs
on generated data this is `true`, and the UI labels it. `GET /api/v1/meta/platform`
reports the platform-wide flag.

Synthetic data presented as real would be the most damaging thing this system
could do, so the label travels with the data rather than being a UI decision.
