package com.citypulse.user.domain;

import com.citypulse.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class User extends BaseEntity {

    /** Stored lower-cased so uniqueness is case-insensitive. */
    @Column(name = "email", nullable = false, length = 255)
    private String email;

    /** BCrypt hash, never the password itself (docs/SECURITY.md §2). */
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = 120)
    private String fullName;

    @Column(name = "organization", length = 160)
    private String organization;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 24)
    private UserStatus status = UserStatus.PENDING_VERIFICATION;

    @Column(name = "email_verified", nullable = false)
    private boolean emailVerified = false;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /**
     * Consecutive failed logins, reset on success. Used for lockout so a
     * distributed attack cannot brute-force a single account past the threshold.
     */
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    /**
     * Foreign key held as a raw id rather than a JPA association: {@code user}
     * and {@code geo} are separate modules, and entity-level coupling between
     * them would break the dependency rule in docs/ARCHITECTURE.md §4.
     */
    @Column(name = "default_city_id")
    private Long defaultCityId;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<Role> roles = new LinkedHashSet<>();

    /** Flattened permission names across all assigned roles. */
    public Set<String> permissionNames() {
        return roles.stream()
                .flatMap(role -> role.getPermissions().stream())
                .map(Permission::getName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public Set<String> roleNames() {
        return roles.stream().map(Role::getName).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public boolean isLocked() {
        return lockedUntil != null && lockedUntil.isAfter(Instant.now());
    }

    /** Active, not soft-deleted, and not currently locked out. */
    public boolean canAuthenticate() {
        return status == UserStatus.ACTIVE && !isDeleted() && !isLocked();
    }
}
