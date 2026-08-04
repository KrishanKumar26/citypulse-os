# CityPulse OS — Security

Security is a first-class requirement (PRD §30). This document records the
threat model, the implemented controls, and the controls still outstanding.
**Nothing here describes an aspiration — items are marked `IMPLEMENTED`,
`PARTIAL`, or `PLANNED` and updated at the end of each milestone.**

---

## 1. Secrets Policy

**No secret is ever committed.** `.gitignore` blocks `.env`, `*.pem`, `*.key`,
`*.jks`, `*.tfvars`, and `application-local.yml`.

| Environment | Secret source |
|---|---|
| Local dev | `.env` file, created from `.env.example`, git-ignored |
| CI | GitHub Actions encrypted secrets |
| AWS | Secrets Manager / SSM Parameter Store, injected as task env vars |

The application **fails to start** if a required secret is missing or left at a
placeholder value. There is no insecure default: `CITYPULSE_JWT_SECRET` has no
fallback in `application.yml`, so a misconfigured deploy crashes loudly instead
of running with a guessable key.

If a secret is ever committed, rotate it first, then rewrite history. Rotation
order matters — history rewriting does not invalidate a leaked credential.

---

## 2. Authentication — `IMPLEMENTED`

### Password storage
- **BCrypt, strength 12.** Plain-text passwords are never stored or logged.
- Password policy enforced server-side: minimum 12 characters, and at least one
  each of uppercase, lowercase, digit, and symbol.
- Password comparison is constant-time (BCrypt's own comparator).

### Tokens

| Token | Type | Lifetime | Storage | Revocable |
|---|---|---|---|---|
| Access | JWT (HS256) | 15 min | Client memory | No — short lifetime is the mitigation |
| Refresh | Opaque 256-bit random | 7 days | SHA-256 hash in `refresh_tokens` | Yes |

**Why the refresh token is opaque, not a JWT:** a JWT refresh token cannot be
revoked without a server-side blocklist, which defeats the point of statelessness.
An opaque random token is looked up on use, so logout and reuse-detection are
immediate and total.

**Rotation with reuse detection.** Every refresh consumes the presented token and
issues a new one. If a token that was already consumed is presented again, that
indicates theft: the entire token family for that user is revoked and the event
is written to the audit log.

Refresh tokens are stored **hashed** (SHA-256). A database disclosure does not
yield usable session credentials.

### Timing-safe login
Login performs a BCrypt comparison against a dummy hash when the account does
not exist, so response time does not reveal whether an email is registered.

### Password reset — `IMPLEMENTED`
Single-use, 30-minute, hashed tokens. The endpoint returns the **same response
whether or not the email exists**, preventing account enumeration. Resetting a
password revokes every refresh token for that user.

---

## 3. Authorization — `IMPLEMENTED`

RBAC with fine-grained permissions. Roles are containers for permissions;
**checks are always made against permissions, never role names**, so adding a
role never requires touching authorization logic.

Roles (PRD §5): `SUPER_ADMIN`, `ADMIN`, `CITY_OPERATOR`, `ANALYST`,
`FLEET_MANAGER`, `DEVELOPER`, `VIEWER`.

Permissions follow `resource:action`, e.g. `city:read`, `city:write`,
`user:manage`, `simulation:create`, `apikey:manage`, `audit:read`.

### Enforcement points
1. **Service layer** — `@PreAuthorize("hasAuthority('city:write')")`. This is the
   authoritative check and is the one covered by tests.
2. **HTTP layer** — route patterns in `SecurityFilterChain` as defence in depth.
3. **Frontend** — navigation and control visibility only. **Never trusted.**

A test asserts that every non-public controller method is reachable only with the
required authority; an endpoint added without a permission check fails the build.

---

## 4. API Security

| Control | Status | Detail |
|---|---|---|
| Input validation | `IMPLEMENTED` | Jakarta Bean Validation on all request DTOs; violations return `422` with per-field messages |
| Consistent errors | `IMPLEMENTED` | `GlobalExceptionHandler`; stack traces and SQL never reach clients |
| Request size limit | `IMPLEMENTED` | 1 MB max body, 2 MB multipart |
| Rate limiting | `IMPLEMENTED` (auth endpoints) | Per-IP token bucket on `/auth/**`: 10 req/min. Per-API-key limits arrive with the API platform module |
| CORS | `IMPLEMENTED` | Explicit origin allow-list from config. No wildcard with credentials |
| Security headers | `IMPLEMENTED` | CSP, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, HSTS in prod profile |
| CSRF | `N/A` | Stateless bearer-token API with no cookie-based auth. Documented rather than silently disabled |
| API keys | `PLANNED` | Phase 9. Stored as SHA-256 hashes; the secret is shown exactly once at creation (PRD §29) |

---

## 5. Database Security

- **Parameterised queries only.** JPA/JPQL with bound parameters; no string
  concatenation into SQL anywhere. Native queries, where used, bind all inputs.
- **Least privilege.** The application connects as `citypulse_app`, which owns no
  DDL rights in production — migrations run as a separate role in a deploy step.
- **No credentials in source.** Connection details come from the environment.
- Soft deletion for referenced records preserves audit integrity.

---

## 6. Audit Logging — `IMPLEMENTED`

Append-only `audit_logs` table. There is no update or delete path in the
application code.

Recorded: login success, login failure, logout, token refresh, refresh-token
reuse detection, password change, password reset request and completion, role
assignment and removal, user creation and deactivation, API key creation and
revocation, and all administrative writes.

Each entry stores actor, action, resource type and identifier, source IP, user
agent, outcome, and timestamp. **Audit entries never contain passwords, tokens,
or token hashes.**

---

## 7. Threat Model

| Threat | Mitigation | Status |
|---|---|---|
| Credential stuffing | Rate limiting, BCrypt cost 12, audit of failed logins | `IMPLEMENTED` |
| Account enumeration | Uniform responses on login and password reset; constant-time login path | `IMPLEMENTED` |
| Token theft | 15-minute access tokens; refresh rotation with reuse detection | `IMPLEMENTED` |
| Privilege escalation | Permission checks at the service layer; role changes audited | `IMPLEMENTED` |
| SQL injection | Parameterised queries only | `IMPLEMENTED` |
| Mass assignment | Request DTOs are distinct from entities; no entity binding | `IMPLEMENTED` |
| Information disclosure via errors | Global handler with sanitised messages | `IMPLEMENTED` |
| IDOR | Internal `BIGSERIAL` keys not exposed; public identifiers are UUIDs; ownership checked in services | `IMPLEMENTED` |
| Denial of service | Rate limits, pagination, request size limits, query timeouts | `PARTIAL` — per-key limits pending |
| Supply chain | Dependency scanning in CI (OWASP Dependency-Check, `npm audit`) | `PLANNED` — Phase 8 |
| Secret leakage | `.gitignore`, secret scanning in CI, no defaults for sensitive config | `PARTIAL` — CI scanning pending |

---

## 8. Security Testing

Required before any milestone is called complete:

- Unauthenticated access to a protected endpoint returns `401`.
- Authenticated access without the required permission returns `403`.
- Expired, malformed, and wrong-signature tokens are all rejected.
- A consumed refresh token cannot be reused, and reuse revokes the family.
- Password policy violations are rejected server-side, not only in the UI.
- Login responses do not differ between unknown email and wrong password.
- Validation failures never echo unsanitised input back to the client.

These live in `backend/src/test/java/com/citypulse/security/`.

---

## 9. Reporting

Security issues should be reported privately to the maintainer rather than filed
as public issues.

| Date | Change |
|---|---|
| 2026-08-03 | Initial security model for the authentication and RBAC foundation. |
