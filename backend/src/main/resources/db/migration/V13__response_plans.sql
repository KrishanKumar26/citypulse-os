-- =============================================================================
-- V13 — Response plans (PRD §16)
--
-- Between "something is wrong" and "we did something about it" there was
-- nothing. Alerts state a condition, interventions record an action already
-- taken, and neither holds the part in the middle: what we intend to do, in
-- what order, and who is doing it.
--
-- A plan is authored, never generated. The platform can offer one step and only
-- one — the recommendedAction an alert rule attached when it fired, which is a
-- real string computed by a real rule. Everything else is typed by a person.
-- A plausible list of steps assembled by the system would be indistinguishable
-- from a considered one, and this is the screen where that mistake would be
-- acted on rather than merely read.
--
-- Steps carry their own status because a plan is rarely all-or-nothing: three
-- of five done with the fourth blocked is the normal state of an operational
-- response, and a single plan-level status cannot say it.
--
-- The link to `interventions` is what closes the loop. A step marked done can
-- name the intervention that recorded the action, and that intervention carries
-- the measurement of what followed. Nullable, because a step can be completed
-- without anything measurable happening — "notified the response team" changes
-- no telemetry.
-- =============================================================================

CREATE TABLE response_plans (
    id             BIGSERIAL PRIMARY KEY,
    uid            UUID          NOT NULL UNIQUE,

    title          VARCHAR(200)  NOT NULL,
    summary        VARCHAR(1000),

    city_id        BIGINT        NOT NULL REFERENCES cities (id) ON DELETE RESTRICT,
    -- Nullable: a city-wide response is legitimate.
    zone_id        BIGINT        REFERENCES zones (id) ON DELETE RESTRICT,

    -- What prompted this. Nullable because a plan can be written from judgement
    -- rather than from a raised alert, and pretending otherwise would force an
    -- operator to invent a trigger.
    alert_id       BIGINT        REFERENCES alerts (id) ON DELETE SET NULL,

    priority       VARCHAR(16)   NOT NULL DEFAULT 'MEDIUM',
    status         VARCHAR(16)   NOT NULL DEFAULT 'DRAFT',

    created_by     BIGINT        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,
    -- Who is carrying it out, if anyone yet. Distinct from the author: writing
    -- a plan and owning it are different acts.
    assigned_to    BIGINT        REFERENCES users (id) ON DELETE SET NULL,

    activated_at   TIMESTAMPTZ,
    closed_at      TIMESTAMPTZ,

    demo_data      BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,

    CONSTRAINT ck_plans_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_plans_status CHECK (status IN ('DRAFT', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    -- A closed plan must record when. An open one must not claim to have.
    CONSTRAINT ck_plans_closed CHECK (
        (status IN ('COMPLETED', 'CANCELLED') AND closed_at IS NOT NULL)
        OR (status IN ('DRAFT', 'ACTIVE') AND closed_at IS NULL)),
    CONSTRAINT ck_plans_close_order CHECK (
        closed_at IS NULL OR activated_at IS NULL OR closed_at >= activated_at)
);

CREATE TABLE response_plan_steps (
    id             BIGSERIAL PRIMARY KEY,
    uid            UUID          NOT NULL UNIQUE,

    plan_id        BIGINT        NOT NULL REFERENCES response_plans (id) ON DELETE CASCADE,

    -- Explicit, not derived from insertion order: steps get reordered, and a
    -- response whose order depends on when rows were typed is not a plan.
    position       INTEGER       NOT NULL,
    instruction    VARCHAR(500)  NOT NULL,

    -- True only for the one step the platform can legitimately supply: the
    -- recommendedAction an alert rule attached when it fired. Recorded so the
    -- UI can show which line came from a rule and which a person wrote, rather
    -- than presenting both in the same voice.
    from_alert_rule BOOLEAN      NOT NULL DEFAULT FALSE,

    status         VARCHAR(16)   NOT NULL DEFAULT 'PENDING',
    -- Why a step is blocked or skipped. Without it, a stalled plan says only
    -- that it stalled.
    note           VARCHAR(500),

    completed_at   TIMESTAMPTZ,
    completed_by   BIGINT        REFERENCES users (id) ON DELETE SET NULL,

    -- The action this step produced, when it produced a measurable one.
    -- Nullable: "notified the response team" changes no telemetry, and forcing
    -- an intervention row for it would fill the impact list with unmeasurable
    -- entries.
    intervention_id BIGINT       REFERENCES interventions (id) ON DELETE SET NULL,

    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- A step removed from a plan is soft-deleted rather than erased: the plan's
    -- history is the record of what was intended, and a line someone decided
    -- against is part of that.
    deleted_at     TIMESTAMPTZ,

    CONSTRAINT ck_steps_status CHECK (status IN ('PENDING', 'DONE', 'BLOCKED', 'SKIPPED')),
    CONSTRAINT ck_steps_done CHECK (
        (status = 'DONE' AND completed_at IS NOT NULL)
        OR (status <> 'DONE' AND completed_at IS NULL)),
    -- A blocked or skipped step without a reason is a dead end nobody can pick
    -- up later.
    CONSTRAINT ck_steps_reason CHECK (
        status NOT IN ('BLOCKED', 'SKIPPED') OR note IS NOT NULL),
    CONSTRAINT uq_steps_plan_position UNIQUE (plan_id, position)
);

CREATE INDEX idx_plans_city_status ON response_plans (city_id, status, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_steps_plan ON response_plan_steps (plan_id, position);

COMMENT ON TABLE response_plans IS
    'What we intend to do about a situation. Authored by a person; the platform supplies at most the recommendedAction a rule already computed.';

COMMENT ON COLUMN response_plan_steps.from_alert_rule IS
    'True for the single step the platform may supply. Everything else was typed by someone, and the UI must not present the two in the same voice.';
