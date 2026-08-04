package com.citypulse.user.domain;

import com.citypulse.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * A named bundle of permissions (PRD §5).
 *
 * <p>{@code systemRole} marks the seven roles the platform depends on; they
 * cannot be deleted or renamed through the API.
 */
@Entity
@Table(name = "roles")
@Getter
@Setter
@NoArgsConstructor
public class Role extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 32)
    private String name;

    @Column(name = "display_name", nullable = false, length = 64)
    private String displayName;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole = false;

    /**
     * Eager because authorities are needed on every authenticated request and the
     * set is small and bounded; lazily loading it would add a query per request.
     */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions = new LinkedHashSet<>();
}
