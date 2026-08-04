package com.citypulse.user.domain;

import com.citypulse.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A single {@code resource:action} capability, e.g. {@code city:write}.
 *
 * <p>Authorization always checks permissions, never role names, so introducing a
 * role never requires editing authorization logic (docs/SECURITY.md §3).
 */
@Entity
@Table(name = "permissions")
@Getter
@Setter
@NoArgsConstructor
public class Permission extends BaseEntity {

    @Column(name = "name", nullable = false, unique = true, length = 64)
    private String name;

    @Column(name = "resource", nullable = false, length = 32)
    private String resource;

    @Column(name = "action", nullable = false, length = 32)
    private String action;

    @Column(name = "description", length = 255)
    private String description;
}
