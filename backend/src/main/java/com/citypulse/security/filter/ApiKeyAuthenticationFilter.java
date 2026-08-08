package com.citypulse.security.filter;

import com.citypulse.apikey.service.ApiKeyService;
import com.citypulse.security.jwt.AuthenticatedPrincipal;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Authenticates a request presenting an API key (PRD §22).
 *
 * <p>Its own header, {@code X-API-Key}, rather than sharing {@code Bearer}. Two
 * credential kinds on one header means guessing which was meant, and a key
 * mistakenly sent as a bearer token would be parsed as a JWT, fail, and produce
 * an error about token format that says nothing useful.
 *
 * <p>The authorities granted are the key's scopes, not the owner's current
 * permissions. That is the whole point of freezing scopes at issue: a key is a
 * narrower credential than the person who made it, and it must stay narrow even
 * as their roles change.
 *
 * <p>Roles are deliberately not granted. A key carries permissions only, so
 * anything gated on a role rather than a permission is reachable by a human
 * session and not by a key — which is the safer default for a credential that
 * lives in someone's deployment configuration.
 */
@Component
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-API-Key";

    private final ApiKeyService apiKeyService;

    public ApiKeyAuthenticationFilter(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String presented = request.getHeader(HEADER);

        if (presented != null && !presented.isBlank()
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            apiKeyService.resolve(presented.trim(), clientIp(request)).ifPresent(resolved -> {
                AuthenticatedPrincipal principal = principalFor(resolved);

                List<SimpleGrantedAuthority> authorities = new ArrayList<>();
                resolved.scopes().forEach(scope -> authorities.add(new SimpleGrantedAuthority(scope)));

                var authentication =
                        new UsernamePasswordAuthenticationToken(principal, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            });
        }

        chain.doFilter(request, response);
    }

    /**
     * The key acts as its owner, with the key's scopes.
     *
     * <p>Roles are empty rather than copied: the key's authority is its scopes
     * and nothing else, and a role slipping in here would grant whatever that
     * role implies today.
     */
    private AuthenticatedPrincipal principalFor(ApiKeyService.Resolved resolved) {
        return new AuthenticatedPrincipal(
                resolved.ownerUid(),
                resolved.ownerEmail(),
                resolved.ownerName(),
                java.util.Set.of(),
                resolved.scopes());
    }

    /**
     * Best-effort client address, for the last-used record.
     *
     * <p>{@code X-Forwarded-For} is taken when present because the application
     * runs behind a proxy, and its first entry is the original client. It is
     * caller-supplied and therefore not trusted for anything: this value is
     * recorded for an operator's benefit and is never used to authorise.
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.length() <= 45 ? remote : remote.substring(0, 45);
    }
}
