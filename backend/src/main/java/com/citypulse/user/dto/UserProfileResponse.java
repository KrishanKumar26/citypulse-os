package com.citypulse.user.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The caller's own profile. Includes the flattened permission list so the
 * frontend can hide controls the user cannot use — presentation only; the API
 * enforces the same permissions independently (docs/SECURITY.md §3).
 */
public record UserProfileResponse(
        UUID id,
        String email,
        String fullName,
        String organization,
        String status,
        boolean emailVerified,
        List<String> roles,
        List<String> permissions,
        Instant lastLoginAt,
        Instant createdAt
) {
}
