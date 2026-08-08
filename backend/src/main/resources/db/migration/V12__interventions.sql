-- =============================================================================
-- V12 — Interventions, and measuring whether they worked (PRD §16)
--
-- The last stage of the product loop: observe, understand, predict, simulate,
-- act, and then find out whether acting helped.
--
-- Two ideas are kept apart deliberately, because collapsing them is how a
-- dashboard starts inventing causation:
--
-- 1. The intervention is a *claim by a person*. The platform cannot observe a
--    traffic officer rerouting a corridor; someone records that they did. So
--    these rows carry who said it and when, and are never generated.
--
-- 2. The measurement is the *platform's*, taken from its own curated windows
--    before and after the stated time. Nothing here stores a computed outcome:
--    it is derived on read, so a corrected window or a relearned baseline
--    changes the answer rather than leaving a stale verdict behind.
--
-- The distinction that makes the measurement worth anything is the baseline.
-- Congestion falls in the evening whether or not anyone intervened, so a raw
-- before/after difference credits the intervention with the sunset. The
-- comparison is against what this zone normally does at this hour of the week —
-- the same zone_baselines the anomaly detector uses — and the *excess* over
-- normal is the only figure that says anything about the action.
--
-- Even that is not proof. It is a measured coincidence between a stated action
-- and a departure from normal, and the API says so.
-- =============================================================================

CREATE TABLE interventions (
    id               BIGSERIAL PRIMARY KEY,
    uid              UUID          NOT NULL UNIQUE,

    -- What was done, in the words of whoever did it.
    title            VARCHAR(200)  NOT NULL,
    description      VARCHAR(1000),

    -- Free text rather than an enum. The set of things a city can do is not
    -- known in advance, and a wrong enum forces operators to file real actions
    -- under "OTHER", which destroys the only field that says what happened.
    action_type      VARCHAR(64)   NOT NULL,

    -- Where. Nullable city-wide actions are legitimate — a public advisory is
    -- not attached to a junction — but then no zone baseline applies and the
    -- API reports that rather than picking a zone.
    zone_id          BIGINT        REFERENCES zones (id) ON DELETE RESTRICT,
    city_id          BIGINT        NOT NULL REFERENCES cities (id) ON DELETE RESTRICT,

    -- When. `ended_at` null means still in effect; the measurement then runs to
    -- now and is labelled provisional.
    started_at       TIMESTAMPTZ   NOT NULL,
    ended_at         TIMESTAMPTZ,

    status           VARCHAR(16)   NOT NULL DEFAULT 'ACTIVE',

    -- Who claimed it. An intervention with no author is an assertion nobody
    -- owns, and the whole record rests on someone having been there.
    recorded_by      BIGINT        NOT NULL REFERENCES users (id) ON DELETE RESTRICT,

    -- How much history to compare against, so a reader can tell a judgement
    -- made over ten minutes from one made over two hours.
    comparison_minutes INTEGER     NOT NULL DEFAULT 60,

    notes            VARCHAR(1000),

    demo_data        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT ck_interventions_status CHECK (status IN (
        'ACTIVE', 'COMPLETED', 'ABANDONED')),
    -- An intervention that ended before it started is a data-entry error, not a
    -- fact about the city.
    CONSTRAINT ck_interventions_time_order CHECK (
        ended_at IS NULL OR ended_at >= started_at),
    -- A comparison window of zero would divide the measurement by nothing.
    CONSTRAINT ck_interventions_comparison CHECK (
        comparison_minutes BETWEEN 5 AND 1440),
    -- COMPLETED means it finished; the row has to say when.
    CONSTRAINT ck_interventions_completed CHECK (
        status <> 'COMPLETED' OR ended_at IS NOT NULL)
);

-- The Action Center's default view, and the impact list: newest first.
CREATE INDEX idx_interventions_city_started ON interventions (city_id, started_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_interventions_zone ON interventions (zone_id, started_at DESC)
    WHERE deleted_at IS NULL AND zone_id IS NOT NULL;

COMMENT ON TABLE interventions IS
    'Actions a person states they took. The platform measures what followed against the zone''s own baseline; it never generates these rows and never claims the action caused the change.';
