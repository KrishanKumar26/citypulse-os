package com.citypulse.security.filter;

import com.citypulse.common.exception.Exceptions;
import com.citypulse.security.jwt.AuthenticatedPrincipal;
import com.citypulse.security.jwt.JwtService;
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
import java.util.List;

/**
 * Authenticates bearer tokens and populates the security context.
 *
 * <p>Permissions are installed as Spring authorities <em>verbatim</em> (e.g.
 * {@code city:read}) with no {@code ROLE_} prefix, so {@code @PreAuthorize}
 * expressions use {@code hasAuthority('city:read')}. Roles are added separately
 * with the {@code ROLE_} prefix for the rare case where a role check is genuinely
 * what is wanted — authorization otherwise always checks permissions
 * (docs/SECURITY.md §3).
 *
 * <p>An invalid token does not throw here. The context is simply left
 * unauthenticated and the request continues, so the entry point produces a
 * consistent 401 envelope; throwing from a filter would bypass
 * {@code GlobalExceptionHandler} and emit Spring's default error body.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                AuthenticatedPrincipal principal = jwtService.parseAccessToken(token);

                List<SimpleGrantedAuthority> authorities = new java.util.ArrayList<>();
                principal.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p)));
                principal.roles().forEach(r -> authorities.add(new SimpleGrantedAuthority("ROLE_" + r)));

                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (Exceptions.InvalidToken ex) {
                // Leave the context unauthenticated; the entry point handles the response.
                SecurityContextHolder.clearContext();
                logger.debug("Bearer token rejected: " + ex.getMessage());
            }
        }

        chain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
