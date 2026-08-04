package com.citypulse.security.filter;

import com.citypulse.common.api.ApiError;
import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.config.SecurityProperties;
import com.citypulse.common.web.RequestIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP fixed-window rate limit on authentication endpoints, to slow credential
 * stuffing and password-reset abuse (docs/SECURITY.md §4).
 *
 * <p>Deliberately in-memory and per-instance. A fixed window is coarse — a client
 * can burst across a window boundary — but it is bounded, allocation-free per
 * request, and needs no external dependency. It complements, rather than
 * replaces, per-account lockout, which is enforced in the auth service and is
 * not defeated by rotating source IPs.
 *
 * <p>Running multiple instances multiplies the effective limit by the instance
 * count. When the platform scales horizontally this moves to a shared Redis
 * counter; that is recorded in the development plan rather than pre-built.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofMinutes(1);
    /** Guards against unbounded growth if the filter is targeted with spoofed IPs. */
    private static final int MAX_TRACKED_CLIENTS = 50_000;

    private final SecurityProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public RateLimitFilter(SecurityProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.rateLimit().enabled()) {
            return true;
        }
        // Only the endpoints where an attacker gains something from volume.
        return !request.getRequestURI().startsWith("/api/v1/auth/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String clientKey = clientIp(request);
        int limit = properties.rateLimit().authRequestsPerMinute();

        if (windows.size() > MAX_TRACKED_CLIENTS) {
            windows.clear();
        }

        Window window = windows.compute(clientKey, (key, existing) ->
                (existing == null || existing.isExpired()) ? new Window() : existing);

        int used = window.count.incrementAndGet();
        long resetInSeconds = window.secondsUntilReset();

        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(Math.max(0, limit - used)));
        response.setHeader("X-RateLimit-Reset", String.valueOf(resetInSeconds));

        if (used > limit) {
            writeRateLimited(response, resetInSeconds);
            return;
        }

        chain.doFilter(request, response);
    }

    /**
     * Uses the leftmost {@code X-Forwarded-For} entry when present. This is only
     * trustworthy behind a proxy that overwrites the header; direct exposure of
     * the service to the internet would let a client spoof it. Deployment always
     * places the service behind ALB/CloudFront (docs/ARCHITECTURE.md §9).
     */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private void writeRateLimited(HttpServletResponse response, long resetInSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(resetInSeconds));

        ApiError error = ApiError.of("RATE_LIMIT_EXCEEDED",
                "Too many requests. Please retry in %d seconds.".formatted(resetInSeconds),
                MDC.get(RequestIdFilter.REQUEST_ID_KEY));
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.failure(error));
    }

    private static final class Window {
        private final Instant startedAt = Instant.now();
        private final AtomicInteger count = new AtomicInteger();

        boolean isExpired() {
            return startedAt.plus(WINDOW).isBefore(Instant.now());
        }

        long secondsUntilReset() {
            long remaining = Duration.between(Instant.now(), startedAt.plus(WINDOW)).toSeconds();
            return Math.max(1, remaining);
        }
    }
}
