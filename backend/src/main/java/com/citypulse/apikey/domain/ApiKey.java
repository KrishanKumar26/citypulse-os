package com.citypulse.apikey.domain;

import com.citypulse.common.domain.BaseEntity;
import com.citypulse.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A credential for programmatic access (PRD §22).
 *
 * <p>The secret is not here. {@code keyHash} is a SHA-256 of it, so a leaked
 * database yields hashes rather than keys, and the key itself is shown once at
 * creation and never again — there is nothing to recover it from.
 *
 * <p>{@code keyPrefix} lets a key be named without being revealed: support can
 * ask about "the one starting cp_live_8fA2", a log can record which key acted,
 * and a list can be rendered, none of which needs the secret. It is not a
 * credential and is deliberately not part of the hash.
 */
@Entity
@Table(name = "api_keys")
@Getter
@Setter
@NoArgsConstructor
public class ApiKey extends BaseEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "key_prefix", nullable = false, length = 24, updatable = false)
    private String keyPrefix;

    @Column(name = "key_hash", nullable = false, length = 64, updatable = false)
    private String keyHash;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    /**
     * Space-separated permissions, frozen at issue.
     *
     * <p>Not a join to the role tables. A key whose authority widened because
     * its owner was later given a new role would hold power nobody decided to
     * grant it; if the grant should change, the key is reissued.
     */
    @Column(name = "scopes", nullable = false, length = 500, updatable = false)
    private String scopes;

    /** Null means no expiry — permitted, but the weaker choice. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 200)
    private String revokedReason;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "last_used_ip", length = 45)
    private String lastUsedIp;

    /** Usable right now: not revoked, not expired, not deleted. */
    public boolean isActive(Instant now) {
        return getDeletedAt() == null
                && revokedAt == null
                && (expiresAt == null || expiresAt.isAfter(now));
    }

    public Set<String> scopeSet() {
        return new LinkedHashSet<>(Arrays.asList(scopes.trim().split("\\s+")));
    }
}
