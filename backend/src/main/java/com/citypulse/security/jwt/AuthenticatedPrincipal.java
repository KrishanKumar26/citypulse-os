package com.citypulse.security.jwt;

import java.util.Set;
import java.util.UUID;

/**
 * The authenticated caller, reconstructed from access token claims without a
 * database read.
 *
 * <p>Holds the public {@code uid}, never the internal primary key: services that
 * need the entity resolve it by uid, which keeps sequential ids out of tokens
 * and logs.
 */
public record AuthenticatedPrincipal(
        UUID userUid,
        String email,
        String fullName,
        Set<String> roles,
        Set<String> permissions
) {

    public boolean hasPermission(String permission) {
        return permissions.contains(permission);
    }
}
