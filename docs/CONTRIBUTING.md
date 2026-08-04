# Contributing to CityPulse OS

Conventions this codebase actually follows, and the reasoning behind the ones
that are unusual. Not aspirational — everything here is visible in the existing
code.

---

## 1. The rule everything else follows from

**The platform may only say things it can point at data for.**

Most of the conventions below are consequences of it. When a decision is
unclear, this is the tiebreaker.

Concretely, in code:

```java
// No.
BigDecimal occupancy = metric.getOccupancy() == null ? BigDecimal.ZERO : ...;

// Yes. Unmeasured is null, and the client renders "not measured".
BigDecimal occupancy = metric.getOccupancy();
```

Zero is a measurement. Null is an absence. A dashboard that renders a dead feed
as an empty road is worse than one that renders nothing.

---

## 2. Comments

Comments explain **why**, never what. The code already says what it does.

```java
// No — restates the code.
// Loop through the zones and add up the capacity.

// Yes — explains a decision that is not obvious.
// Averages are taken only over zones that actually reported. Including silent
// zones as zeroes would drag every city average toward zero as feeds dropped
// out — the dashboard would look calmer precisely as the platform lost
// visibility, which is the most dangerous direction for it to be wrong.
```

Prefer a comment on the thing a reader would otherwise change by mistake. The
most valuable comments in this codebase are the ones recording a defect:

```python
# 3.x, not the long-standing 2.0.2: that release predates Python 3.12 and dies
# on import with `No module named 'kafka.vendor.six.moves'`. The failure is
# invisible until something actually talks to a broker, because the import is
# lazy — so it looked fine right up to the first real Kafka run.
```

Class and module docstrings state the purpose and the significant trade-off, not
a list of methods.

---

## 3. Tests

### Test names are sentences

```java
@DisplayName("a zone with no telemetry reports null, not zero")
@DisplayName("a resolved alert cannot be reopened")
@DisplayName("confidence falls as measured error rises")
```

A failing test should tell you what broke without opening the file.

### Test the property, not the output

Especially for anything computed. Asserting a specific number pins whatever the
code happens to do today; asserting a property pins what it is *supposed* to do.

```java
// Weak — passes even if the model is nonsense, as long as it stays nonsense.
assertThat(outcome.simulatedOccupancy()).isEqualTo(0.7523);

// Strong — states the model's claim about itself.
assertThat(heavyRain).isGreaterThanOrEqualTo(lightRain);
```

### Test what must not happen

The most valuable tests here assert refusals:

- a thin baseline **declines to judge** rather than guessing
- a scenario **refuses to run** without a real observed baseline
- a resolved alert **cannot** be reopened
- features **ignore everything after** the issue point

The leakage test is the model: poison every future window with absurd values and
assert the features are byte-identical. No accuracy metric could ever reveal that
bug.

### Integration tests use a real database

`citypulse_test`, real PostgreSQL. The schema depends on partial unique indexes,
check constraints and `TIMESTAMPTZ` semantics that an in-memory substitute does
not reproduce.

Tests are **not** wrapped in a rollback transaction. Several behaviours commit in
`REQUIRES_NEW` transactions — lockout counters, token-family revocation, audit
entries — and a surrounding rollback would hide exactly the behaviour under test.
State is reset explicitly in `IntegrationTest.resetMutableState()`.

**When you add a table that a test can write to, add it there.** A leftover row
becomes an order-dependent failure in an unrelated test, which reads as a flake
and erodes trust in the whole suite.

---

## 4. Layout

### Backend

```
com.citypulse.<module>/
  domain/       entities and enums
  repository/   Spring Data interfaces
  dto/          request and response records
  service/      business logic; @PreAuthorize lives here
  controller/   HTTP only — no logic
```

Authorisation is on the **service**, not the controller. A second caller — a
scheduled job, another service — must not be able to bypass it by not going
through HTTP.

### Data platform

```
common/         contracts, validation, transforms — the single source of truth
generator/      synthetic city
pipeline/       local runner, Spark job, loader
ml/             features, model, scoring
intelligence/   detection, baselines, memory, correlations
dbt/            staging → intermediate → marts
```

**`common/` has no Spark or Kafka imports.** That is what lets the Spark job and
the local runner drive identical logic. Do not add framework dependencies to it.

---

## 5. Database

Every schema change is a Flyway migration. Never edit an applied one.

Migrations carry a header explaining the *design*, not the syntax:

```sql
-- Alerts are derived, not ingested: a rule evaluates curated zone metrics and
-- raises one when a condition holds. That makes them the first thing in the
-- platform that says something rather than only reports something, so two
-- properties matter more than usual.
```

Conventions in use:

- `BIGSERIAL` internal id, `UUID uid` for public addressing
- `TIMESTAMPTZ` always; never a naive timestamp
- Soft delete via `deleted_at` where history matters
- `CHECK` constraints for enums, so the database refuses bad states too
- Partial unique indexes where uniqueness is conditional
- BRIN indexes on large append-only time columns

---

## 6. Frontend

- Server components by default; `"use client"` only where interactivity requires
- `@tanstack/react-query` for data — not `useEffect` with `setState`
- No `setState` inside an effect. To reset on a prop change, adjust state during
  render (React's documented pattern). Doing it in an effect renders one frame of
  the previous entity's data under the new one's name.
- Every list has an explicit empty state saying what would fill it
- Every fetch has an explicit error state with a retry
- A module that is not built renders `ComingSoon` stating what is missing —
  never controls that do nothing

---

## 7. Secrets

- No secret has a default. The application refuses to start without one rather
  than falling back to something guessable.
- `.env` and `.env.*` are gitignored; `.env.example` carries the *command to
  generate* each value, never a value.
- Never log a token, password or key. Not at debug level either.

---

## 8. Before you commit

```bash
cd backend        && ./mvnw verify        # 197
cd data-engineering && .venv/bin/python -m pytest   # 187
cd data-engineering/dbt && dbt build      # 108
cd frontend       && npm test && npm run lint && npm run build   # 51
```

All four must pass. If you changed the pipeline, run the verification scripts in
[`verification/`](verification/) against a running stack as well.

### Commit messages

State what changed and why it was worth changing. Record defects found — the
commit message is often the only place the reasoning survives:

```
That measurement forced a real change. At 3.5 sigma the detector produced a 4%
false-positive rate on occupancy, roughly eighty times what the threshold
implies for normal data, because traffic occupancy is a product of peak demand,
weather and incidents and its tails are far fatter than normal.
```

---

## 9. Adding a phase

`docs/DEVELOPMENT_PLAN.md` is the record of what is real. When you complete
work:

1. Tick only the exit criteria you **verified**, and say how.
2. Record what you could not verify, and why. A limitation stated is a
   limitation someone can plan around; one omitted is a trap.
3. Record defects found, especially ones the tests caught. They are the most
   useful thing in the document.

The plan is not a status report for someone else. It is what stops the next
person — likely you — from assuming something works because it was on a list.
