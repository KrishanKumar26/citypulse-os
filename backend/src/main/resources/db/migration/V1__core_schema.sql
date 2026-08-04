-- =============================================================================
-- V1 — Core schema: RBAC, authentication tokens, geography, audit log
--
-- Conventions (docs/ARCHITECTURE.md §5):
--   * BIGSERIAL internal key + UUID public identifier
--   * TIMESTAMPTZ everywhere, stored in UTC
--   * deleted_at for soft deletion on referenced records
--   * an index for every foreign key and every filtered column
--   * partial unique indexes so a soft-deleted row frees its unique value
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Permissions
-- -----------------------------------------------------------------------------
CREATE TABLE permissions (
    id          BIGSERIAL PRIMARY KEY,
    uid         UUID        NOT NULL UNIQUE,
    name        VARCHAR(64) NOT NULL,
    resource    VARCHAR(32) NOT NULL,
    action      VARCHAR(32) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at  TIMESTAMPTZ,
    CONSTRAINT uq_permissions_name UNIQUE (name),
    CONSTRAINT ck_permissions_name_format CHECK (name = resource || ':' || action)
);

CREATE INDEX idx_permissions_resource ON permissions (resource);

-- -----------------------------------------------------------------------------
-- Roles
-- -----------------------------------------------------------------------------
CREATE TABLE roles (
    id           BIGSERIAL PRIMARY KEY,
    uid          UUID        NOT NULL UNIQUE,
    name         VARCHAR(32) NOT NULL,
    display_name VARCHAR(64) NOT NULL,
    description  VARCHAR(255),
    system_role  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at   TIMESTAMPTZ,
    CONSTRAINT uq_roles_name UNIQUE (name)
);

CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL REFERENCES roles (id) ON DELETE CASCADE,
    permission_id BIGINT NOT NULL REFERENCES permissions (id) ON DELETE CASCADE,
    PRIMARY KEY (role_id, permission_id)
);

-- The PK covers role_id; this index serves the reverse lookup
-- ("which roles grant this permission?") used by the admin UI.
CREATE INDEX idx_role_permissions_permission_id ON role_permissions (permission_id);

-- -----------------------------------------------------------------------------
-- Cities and zones
-- -----------------------------------------------------------------------------
CREATE TABLE cities (
    id               BIGSERIAL PRIMARY KEY,
    uid              UUID          NOT NULL UNIQUE,
    slug             VARCHAR(64)   NOT NULL,
    name             VARCHAR(120)  NOT NULL,
    country          VARCHAR(80)   NOT NULL,
    -- VARCHAR(2), not CHAR(2): CHAR is blank-padded, which would silently make
    -- every comparison and JPA mapping subtly wrong.
    country_code     VARCHAR(2)    NOT NULL,
    timezone         VARCHAR(64)   NOT NULL,
    center_latitude  NUMERIC(9, 6) NOT NULL,
    center_longitude NUMERIC(9, 6) NOT NULL,
    default_zoom     INTEGER       NOT NULL DEFAULT 11,
    population       INTEGER,
    area_sq_km       NUMERIC(10, 2),
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Telemetry for this city is synthetic. Surfaced to the UI (PRD §42).
    demo_data        BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT ck_cities_latitude CHECK (center_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_cities_longitude CHECK (center_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_cities_zoom CHECK (default_zoom BETWEEN 1 AND 20),
    CONSTRAINT ck_cities_population CHECK (population IS NULL OR population >= 0)
);

-- Partial: a soft-deleted city releases its slug for reuse.
CREATE UNIQUE INDEX uq_cities_slug_active ON cities (slug) WHERE deleted_at IS NULL;
CREATE INDEX idx_cities_active ON cities (active) WHERE deleted_at IS NULL;

CREATE TABLE zones (
    id               BIGSERIAL PRIMARY KEY,
    uid              UUID          NOT NULL UNIQUE,
    city_id          BIGINT        NOT NULL REFERENCES cities (id) ON DELETE RESTRICT,
    code             VARCHAR(48)   NOT NULL,
    name             VARCHAR(120)  NOT NULL,
    zone_type        VARCHAR(24)   NOT NULL DEFAULT 'MIXED',
    center_latitude  NUMERIC(9, 6) NOT NULL,
    center_longitude NUMERIC(9, 6) NOT NULL,
    boundary_geojson TEXT,
    area_sq_km       NUMERIC(10, 2),
    population       INTEGER,
    road_capacity_vph INTEGER,
    active           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT ck_zones_latitude CHECK (center_latitude BETWEEN -90 AND 90),
    CONSTRAINT ck_zones_longitude CHECK (center_longitude BETWEEN -180 AND 180),
    CONSTRAINT ck_zones_capacity CHECK (road_capacity_vph IS NULL OR road_capacity_vph > 0),
    CONSTRAINT ck_zones_type CHECK (zone_type IN (
        'RESIDENTIAL', 'COMMERCIAL', 'INDUSTRIAL', 'MIXED',
        'TRANSIT_HUB', 'EDUCATIONAL', 'RECREATIONAL', 'AIRPORT'))
);

-- Zone codes are unique within a city, not globally.
CREATE UNIQUE INDEX uq_zones_city_code_active ON zones (city_id, code) WHERE deleted_at IS NULL;
CREATE INDEX idx_zones_city_id ON zones (city_id);
CREATE INDEX idx_zones_city_active ON zones (city_id, active) WHERE deleted_at IS NULL;

-- -----------------------------------------------------------------------------
-- Users
-- -----------------------------------------------------------------------------
CREATE TABLE users (
    id                    BIGSERIAL PRIMARY KEY,
    uid                   UUID         NOT NULL UNIQUE,
    email                 VARCHAR(255) NOT NULL,
    -- BCrypt hash only. A plain-text password must never reach this column.
    password_hash         VARCHAR(100) NOT NULL,
    full_name             VARCHAR(120) NOT NULL,
    organization          VARCHAR(160),
    status                VARCHAR(24)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
    email_verified        BOOLEAN      NOT NULL DEFAULT FALSE,
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ,
    failed_login_attempts INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    default_city_id       BIGINT REFERENCES cities (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at            TIMESTAMPTZ,
    CONSTRAINT ck_users_status CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'SUSPENDED')),
    CONSTRAINT ck_users_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_users_failed_attempts CHECK (failed_login_attempts >= 0)
);

-- Case-insensitive uniqueness is guaranteed by the lowercase check constraint
-- plus this index; a soft-deleted account releases its address.
CREATE UNIQUE INDEX uq_users_email_active ON users (email) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_status ON users (status) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_default_city_id ON users (default_city_id);

CREATE TABLE user_roles (
    user_id BIGINT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role_id BIGINT NOT NULL REFERENCES roles (id) ON DELETE RESTRICT,
    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_role_id ON user_roles (role_id);

-- -----------------------------------------------------------------------------
-- Authentication tokens
--
-- Both tables store only SHA-256 hashes of the issued secret, so a database
-- disclosure yields nothing usable (docs/SECURITY.md §2). Hard-deleted on
-- expiry, hence no uid or deleted_at.
-- -----------------------------------------------------------------------------
CREATE TABLE refresh_tokens (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_hash     VARCHAR(64) NOT NULL UNIQUE,
    -- All tokens rotated from one login share a family, enabling
    -- family-wide revocation when reuse is detected.
    family_id      UUID        NOT NULL,
    expires_at     TIMESTAMPTZ NOT NULL,
    consumed_at    TIMESTAMPTZ,
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(64),
    client_ip      VARCHAR(45),
    user_agent     VARCHAR(255),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_token_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
CREATE INDEX idx_refresh_tokens_family_id ON refresh_tokens (family_id);
-- Supports the scheduled purge of expired tokens.
CREATE INDEX idx_refresh_tokens_expires_at ON refresh_tokens (expires_at);

-- Single-use tokens for password reset and email verification. One table with a
-- discriminator rather than two near-identical ones: the lifecycle (issue once,
-- consume once, expire) is the same, and only the effect of redemption differs.
-- Refresh tokens stay separate because their rotation and family semantics are
-- genuinely different.
CREATE TABLE user_tokens (
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    token_type VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_tokens_type CHECK (token_type IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION'))
);

CREATE INDEX idx_user_tokens_token_hash ON user_tokens (token_hash);
CREATE INDEX idx_user_tokens_user_type ON user_tokens (user_id, token_type);
CREATE INDEX idx_user_tokens_expires_at ON user_tokens (expires_at);

-- -----------------------------------------------------------------------------
-- Audit log — append only
--
-- No UPDATE or DELETE path exists in application code. actor_id is nullable
-- because pre-authentication events (failed logins) have no known actor, and
-- ON DELETE SET NULL preserves the entry if the user row is ever removed.
-- -----------------------------------------------------------------------------
CREATE TABLE audit_logs (
    id            BIGSERIAL PRIMARY KEY,
    actor_id      BIGINT REFERENCES users (id) ON DELETE SET NULL,
    actor_email   VARCHAR(255),
    action        VARCHAR(48) NOT NULL,
    resource_type VARCHAR(48),
    resource_id   VARCHAR(64),
    outcome       VARCHAR(16) NOT NULL,
    detail        VARCHAR(512),
    client_ip     VARCHAR(45),
    user_agent    VARCHAR(255),
    request_id    VARCHAR(64),
    occurred_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_audit_logs_outcome CHECK (outcome IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

CREATE INDEX idx_audit_logs_actor_id ON audit_logs (actor_id);
CREATE INDEX idx_audit_logs_action ON audit_logs (action);
CREATE INDEX idx_audit_logs_occurred_at ON audit_logs (occurred_at DESC);
CREATE INDEX idx_audit_logs_resource ON audit_logs (resource_type, resource_id);
