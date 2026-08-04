package com.citypulse.user.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.service.AuditService;
import com.citypulse.auth.repository.RefreshTokenRepository;
import com.citypulse.common.api.PageResponse;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.domain.Permissions;
import com.citypulse.user.domain.Role;
import com.citypulse.user.domain.RoleName;
import com.citypulse.user.domain.User;
import com.citypulse.user.domain.UserStatus;
import com.citypulse.user.dto.RoleResponse;
import com.citypulse.user.dto.UserMapper;
import com.citypulse.user.dto.UserProfileResponse;
import com.citypulse.user.dto.UserRequests;
import com.citypulse.user.dto.UserSummaryResponse;
import com.citypulse.user.repository.RoleRepository;
import com.citypulse.user.repository.UserRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * User and role administration.
 *
 * <p>{@code @PreAuthorize} here is the authoritative authorization check
 * (docs/SECURITY.md §3). URL rules in {@code SecurityConfig} are defence in
 * depth, not the control being tested.
 */
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public UserService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       UserMapper userMapper,
                       AuditService auditService,
                       CurrentUser currentUser) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    /**
     * The caller's own profile. No permission required — every authenticated user
     * may read themselves — and it is read from the database rather than the
     * token so a role change takes effect immediately.
     */
    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userUid) {
        return userMapper.toProfile(requireUser(userUid));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public PageResponse<UserSummaryResponse> list(String search, Pageable pageable) {
        String normalised = (search == null || search.isBlank()) ? null : search.trim();
        return PageResponse.from(userRepository.search(normalised, pageable), userMapper::toSummary);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.USER_READ + "')")
    public UserSummaryResponse get(UUID userUid) {
        return userMapper.toSummary(requireUser(userUid));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ROLE_READ + "')")
    public List<RoleResponse> listRoles() {
        return roleRepository.findByDeletedAtIsNullOrderByNameAsc().stream()
                .map(RoleResponse::from)
                .toList();
    }

    /**
     * Replaces a user's roles wholesale.
     *
     * <p>Two guards worth stating: a user cannot change their own roles, which
     * would let any account with {@code user:manage_roles} escalate itself
     * silently; and only a {@code SUPER_ADMIN} may grant {@code SUPER_ADMIN}, so
     * the highest privilege cannot be minted from below it.
     */
    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.USER_MANAGE_ROLES + "')")
    public UserSummaryResponse assignRoles(UUID userUid, UserRequests.AssignRoles request) {
        var actor = currentUser.require();
        User target = requireUser(userUid);

        if (target.getUid().equals(actor.userUid())) {
            throw new Exceptions.Forbidden("You cannot change your own roles");
        }

        Set<String> requested = Set.copyOf(request.roles());
        if (requested.contains(RoleName.SUPER_ADMIN) && !actor.roles().contains(RoleName.SUPER_ADMIN)) {
            throw new Exceptions.Forbidden("Only a super administrator can grant the SUPER_ADMIN role");
        }
        if (target.roleNames().contains(RoleName.SUPER_ADMIN) && !actor.roles().contains(RoleName.SUPER_ADMIN)) {
            throw new Exceptions.Forbidden("Only a super administrator can modify a super administrator");
        }

        List<Role> roles = roleRepository.findByNameInAndDeletedAtIsNull(requested);
        if (roles.size() != requested.size()) {
            Set<String> found = roles.stream().map(Role::getName).collect(java.util.stream.Collectors.toSet());
            String unknown = requested.stream().filter(name -> !found.contains(name))
                    .sorted().collect(java.util.stream.Collectors.joining(", "));
            throw new Exceptions.BadRequest("UNKNOWN_ROLE", "Unknown role(s): " + unknown);
        }

        Set<String> previous = target.roleNames();
        target.getRoles().clear();
        target.getRoles().addAll(roles);
        User saved = userRepository.save(target);

        // Existing access tokens carry the old permissions until they expire.
        // Revoking refresh tokens bounds that window to one access-token lifetime
        // and forces a fresh sign-in for the new set.
        refreshTokenRepository.revokeAllForUser(saved.getId(), "ROLES_CHANGED", Instant.now());

        auditService.recordResourceChange(AuditAction.ROLE_ASSIGNED, null, actor.email(),
                "USER", saved.getUid().toString(),
                "Roles changed from %s to %s".formatted(previous, requested));

        return userMapper.toSummary(saved);
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.USER_WRITE + "')")
    public UserSummaryResponse setStatus(UUID userUid, UserRequests.SetStatus request) {
        var actor = currentUser.require();
        User target = requireUser(userUid);

        if (target.getUid().equals(actor.userUid())) {
            // Prevents an administrator locking themselves out of the platform.
            throw new Exceptions.Forbidden("You cannot change your own account status");
        }
        if (target.roleNames().contains(RoleName.SUPER_ADMIN) && !actor.roles().contains(RoleName.SUPER_ADMIN)) {
            throw new Exceptions.Forbidden("Only a super administrator can modify a super administrator");
        }

        UserStatus status = UserStatus.valueOf(request.status());
        target.setStatus(status);
        if (status == UserStatus.SUSPENDED) {
            target.setLockedUntil(null);
            refreshTokenRepository.revokeAllForUser(target.getId(), "ACCOUNT_SUSPENDED", Instant.now());
        }
        User saved = userRepository.save(target);

        auditService.recordResourceChange(
                status == UserStatus.SUSPENDED ? AuditAction.USER_SUSPENDED : AuditAction.USER_REACTIVATED,
                null, actor.email(), "USER", saved.getUid().toString(),
                "Status set to " + status);

        return userMapper.toSummary(saved);
    }

    private User requireUser(UUID userUid) {
        return userRepository.findByUidAndDeletedAtIsNull(userUid)
                .orElseThrow(() -> new Exceptions.NotFound("User", userUid));
    }
}
