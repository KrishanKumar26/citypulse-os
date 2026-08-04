package com.citypulse.user.dto;

import com.citypulse.user.domain.Permission;
import com.citypulse.user.domain.Role;

import java.util.List;
import java.util.UUID;

public record RoleResponse(
        UUID id,
        String name,
        String displayName,
        String description,
        boolean systemRole,
        List<String> permissions
) {

    public static RoleResponse from(Role role) {
        return new RoleResponse(
                role.getUid(),
                role.getName(),
                role.getDisplayName(),
                role.getDescription(),
                role.isSystemRole(),
                role.getPermissions().stream().map(Permission::getName).sorted().toList()
        );
    }
}
