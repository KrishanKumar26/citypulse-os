package com.citypulse.security;

import com.citypulse.common.exception.Exceptions;
import com.citypulse.security.jwt.AuthenticatedPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Access to the authenticated caller, so services do not each reach into
 * {@link SecurityContextHolder} and re-implement the null and type checks.
 */
@Component
public class CurrentUser {

    public Optional<AuthenticatedPrincipal> find() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        return authentication.getPrincipal() instanceof AuthenticatedPrincipal principal
                ? Optional.of(principal)
                : Optional.empty();
    }

    /**
     * @throws Exceptions.InvalidToken when called outside an authenticated
     *                                 request; endpoints requiring a caller are
     *                                 already behind authentication, so this
     *                                 indicates a wiring mistake rather than a
     *                                 client error
     */
    public AuthenticatedPrincipal require() {
        return find().orElseThrow(() -> new Exceptions.InvalidToken("No authenticated user in context"));
    }
}
