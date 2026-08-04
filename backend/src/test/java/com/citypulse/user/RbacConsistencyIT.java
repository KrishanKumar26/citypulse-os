package com.citypulse.user;

import com.citypulse.support.IntegrationTest;
import com.citypulse.user.domain.Permission;
import com.citypulse.user.domain.Permissions;
import com.citypulse.user.domain.Role;
import com.citypulse.user.domain.RoleName;
import com.citypulse.user.repository.PermissionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps the Java permission constants and the seeded database rows in agreement.
 *
 * <p>{@code @PreAuthorize("hasAuthority('city:write')")} is a string that the
 * compiler never checks. A typo in either the annotation or migration V2 would
 * produce an authority nobody holds — the endpoint would silently deny everyone,
 * or a permission would exist that nothing grants. These tests turn that class of
 * mistake into a build failure (docs/SECURITY.md §3).
 */
@DisplayName("RBAC model consistency")
class RbacConsistencyIT extends IntegrationTest {

    @Autowired
    private PermissionRepository permissionRepository;

    @Test
    @DisplayName("every Permissions constant exists in the database")
    void everyConstantIsSeeded() throws Exception {
        Set<String> seeded = permissionRepository.findByDeletedAtIsNullOrderByNameAsc().stream()
                .map(Permission::getName)
                .collect(Collectors.toSet());

        List<String> declared = declaredPermissionConstants();
        assertThat(declared).isNotEmpty();
        assertThat(seeded)
                .as("a constant with no seeded row means an endpoint nobody can ever reach")
                .containsAll(declared);
    }

    @Test
    @DisplayName("every seeded permission has a Java constant")
    void everySeededPermissionIsDeclared() throws Exception {
        Set<String> declared = Set.copyOf(declaredPermissionConstants());

        assertThat(permissionRepository.findByDeletedAtIsNullOrderByNameAsc().stream()
                .map(Permission::getName)
                .toList())
                .as("a seeded row with no constant is dead data or a forgotten feature")
                .allSatisfy(name -> assertThat(declared).contains(name));
    }

    @Test
    @DisplayName("all seven PRD roles are seeded as system roles")
    void allRolesExist() {
        var expected = List.of(RoleName.SUPER_ADMIN, RoleName.ADMIN, RoleName.CITY_OPERATOR,
                RoleName.ANALYST, RoleName.FLEET_MANAGER, RoleName.DEVELOPER, RoleName.VIEWER);

        var seeded = roleRepository.findByDeletedAtIsNullOrderByNameAsc();
        assertThat(seeded.stream().map(Role::getName).toList()).containsExactlyInAnyOrderElementsOf(expected);
        assertThat(seeded).allSatisfy(role -> assertThat(role.isSystemRole()).isTrue());
    }

    @Test
    @DisplayName("SUPER_ADMIN holds every permission")
    void superAdminHoldsEverything() {
        var superAdmin = roleRepository.findByNameAndDeletedAtIsNull(RoleName.SUPER_ADMIN).orElseThrow();
        var all = permissionRepository.findByDeletedAtIsNullOrderByNameAsc();

        assertThat(superAdmin.getPermissions()).hasSameSizeAs(all);
    }

    @Test
    @DisplayName("VIEWER is read-only: it holds no write, manage, or export permission")
    void viewerIsReadOnly() {
        var viewer = roleRepository.findByNameAndDeletedAtIsNull(RoleName.VIEWER).orElseThrow();

        assertThat(viewer.getPermissions())
                .as("VIEWER is granted on self-service signup, so it must never be able to mutate anything")
                .allSatisfy(permission -> assertThat(permission.getAction()).isEqualTo("read"));
    }

    @Test
    @DisplayName("only SUPER_ADMIN can manage platform configuration")
    void systemManageIsRestricted() {
        var holders = roleRepository.findByDeletedAtIsNullOrderByNameAsc().stream()
                .filter(role -> role.getPermissions().stream()
                        .anyMatch(p -> p.getName().equals(Permissions.SYSTEM_MANAGE)))
                .map(Role::getName)
                .toList();

        assertThat(holders).containsExactly(RoleName.SUPER_ADMIN);
    }

    @Test
    @DisplayName("permission names match the resource:action format the check constraint enforces")
    void permissionNamesAreWellFormed() {
        assertThat(permissionRepository.findByDeletedAtIsNullOrderByNameAsc())
                .allSatisfy(permission -> assertThat(permission.getName())
                        .isEqualTo(permission.getResource() + ":" + permission.getAction()));
    }

    /** Reads the {@code public static final String} fields off {@link Permissions}. */
    private List<String> declaredPermissionConstants() throws Exception {
        List<String> names = new java.util.ArrayList<>();
        for (Field field : Permissions.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class) {
                names.add((String) field.get(null));
            }
        }
        return names;
    }
}
