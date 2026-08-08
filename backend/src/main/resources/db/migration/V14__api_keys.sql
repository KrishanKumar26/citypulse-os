-- =============================================================================
-- V14 — API keys (PRD §22)
--
-- Programmatic access without handing out a user's password or a refresh token
-- that renews itself indefinitely.
--
-- The secret is never stored. `key_hash` holds a SHA-256 of the key, the same
-- treatment refresh tokens get (docs/SECURITY.md §2): 256 bits from a CSPRNG has
-- no guessable structure, so there is nothing for a slow hash to defend against,
-- and the property that matters is that the stored value is not the value the
-- client presents. A leaked database gives an attacker hashes, not keys.
--
-- `key_prefix` exists so a key can be named without being revealed. Support can
-- ask "is it the one starting cp_live_8fA2", logs can record which key acted,
-- and the UI can list keys — none of which requires anyone to hold the secret.
-- It is not a credential and is deliberately not part of the hash.
--
-- Scopes are stored as text rather than joined to the permission table. A key's
-- authority must not silently widen when a role is edited: the grant is what was
-- written at issue time, and if it should change, someone reissues the key.
-- =============================================================================

CREATE TABLE api_keys (
    id             BIGSERIAL PRIMARY KEY,
    uid            UUID          NOT NULL UNIQUE,

    -- What it is for, in the owner's words. Required: an unnamed key cannot be
    -- revoked with any confidence about what will break.
    name           VARCHAR(120)  NOT NULL,
    description    VARCHAR(500),

    -- Identifies the key without revealing it. Unique so a prefix collision
    -- cannot make two keys indistinguishable in a log.
    key_prefix     VARCHAR(24)   NOT NULL UNIQUE,
    -- VARCHAR, matching refresh_tokens. CHAR would space-pad to width, and a
    -- padded hash does not equal the one computed from the presented key —
    -- every lookup would miss, and the failure would look like a wrong key.
    key_hash       VARCHAR(64)   NOT NULL UNIQUE,

    owner_id       BIGINT        NOT NULL REFERENCES users (id) ON DELETE CASCADE,

    -- Space-separated permission strings, frozen at issue. See the note above:
    -- a key that widened when its owner's role changed would grant authority
    -- nobody decided to give it.
    scopes         VARCHAR(500)  NOT NULL,

    -- Null means no expiry, which is allowed but is the weaker choice; the API
    -- states the trade-off rather than forcing one.
    expires_at     TIMESTAMPTZ,

    -- Revocation is a timestamp, not a delete. A key that acted must remain
    -- explicable afterwards, and the audit log references it.
    revoked_at     TIMESTAMPTZ,
    revoked_reason VARCHAR(200),

    -- Written on use so an unused key can be found and retired. Deliberately
    -- coarse: updating it on every request would put a write on the hot path of
    -- every authenticated call.
    last_used_at   TIMESTAMPTZ,
    last_used_ip   VARCHAR(45),

    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    deleted_at     TIMESTAMPTZ,

    CONSTRAINT ck_api_keys_expiry CHECK (expires_at IS NULL OR expires_at > created_at),
    -- A revoked key must say when. An active one must not claim to have been.
    CONSTRAINT ck_api_keys_revocation CHECK (
        (revoked_at IS NULL AND revoked_reason IS NULL)
        OR (revoked_at IS NOT NULL)),
    CONSTRAINT ck_api_keys_scopes CHECK (length(trim(scopes)) > 0)
);

-- The lookup on every authenticated request: hash to key, active only.
CREATE INDEX idx_api_keys_hash_active ON api_keys (key_hash)
    WHERE deleted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX idx_api_keys_owner ON api_keys (owner_id, created_at DESC)
    WHERE deleted_at IS NULL;

COMMENT ON COLUMN api_keys.key_hash IS
    'SHA-256 of the key. The key itself is shown once, at creation, and is not recoverable from here.';

COMMENT ON COLUMN api_keys.scopes IS
    'Frozen at issue. A key must not gain authority because its owner''s role was later widened.';
