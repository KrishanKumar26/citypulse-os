package com.citypulse.user.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user as seen by an administrator listing accounts. Carries no permission
 * detail — that is derivable from roles and would bloat every list row.
 */
public record UserSummaryResponse(
        UUID id,
        String email,
        String fullName,
        String organization,
        String status,
        boolean emailVerified,
        List<String> roles,
        Instant lastLoginAt,
        Instant createdAt
) {
}
