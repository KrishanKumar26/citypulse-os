package com.citypulse.user.dto;

import com.citypulse.user.domain.User;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Entity to DTO mapping. Hand-written rather than generated: it is the boundary
 * that stops entities leaking through the API (PRD §26), so it is worth being
 * explicit about exactly which fields cross it. Note that {@code passwordHash},
 * {@code failedLoginAttempts}, and {@code lockedUntil} deliberately never do.
 */
@Component
public class UserMapper {

    public UserProfileResponse toProfile(User user) {
        return new UserProfileResponse(
                user.getUid(),
                user.getEmail(),
                user.getFullName(),
                user.getOrganization(),
                user.getStatus().name(),
                user.isEmailVerified(),
                List.copyOf(user.roleNames()),
                List.copyOf(user.permissionNames()),
                user.getLastLoginAt(),
                user.getCreatedAt()
        );
    }

    public UserSummaryResponse toSummary(User user) {
        return new UserSummaryResponse(
                user.getUid(),
                user.getEmail(),
                user.getFullName(),
                user.getOrganization(),
                user.getStatus().name(),
                user.isEmailVerified(),
                List.copyOf(user.roleNames()),
                user.getLastLoginAt(),
                user.getCreatedAt()
        );
    }
}
