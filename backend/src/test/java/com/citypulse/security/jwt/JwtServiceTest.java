package com.citypulse.security.jwt;

import com.citypulse.common.config.SecurityProperties;
import com.citypulse.common.exception.Exceptions;

import com.citypulse.user.domain.Permission;
import com.citypulse.user.domain.Role;
import com.citypulse.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for token issuing and verification. No Spring context: these are
 * pure cryptographic and claim-handling assertions and should run in milliseconds.
 */
@DisplayName("JwtService")
class JwtServiceTest {

    private static final String SECRET = "unit-test-signing-key-at-least-32-bytes-long-0123456789";
    private static final String ISSUER = "citypulse-os";

    private JwtService jwtService;
    private User user;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(propertiesWith(SECRET, Duration.ofMinutes(15)));
        jwtService.initialiseKey();
        user = userWithPermissions("ops@example.com", "CITY_OPERATOR", "city:read", "alert:manage");
    }

    @Test
    @DisplayName("issues a token whose claims round-trip intact")
    void issuesAndParsesToken() {
        var issued = jwtService.issueAccessToken(user);
        var principal = jwtService.parseAccessToken(issued.token());

        assertThat(principal.userUid()).isEqualTo(user.getUid());
        assertThat(principal.email()).isEqualTo("ops@example.com");
        assertThat(principal.roles()).containsExactly("CITY_OPERATOR");
        assertThat(principal.permissions()).containsExactlyInAnyOrder("city:read", "alert:manage");
        assertThat(principal.hasPermission("city:read")).isTrue();
        assertThat(principal.hasPermission("city:write")).isFalse();
    }

    @Test
    @DisplayName("uses the public uid as subject, never the internal primary key")
    void subjectIsPublicIdentifier() {
        var issued = jwtService.issueAccessToken(user);
        String payload = new String(java.util.Base64.getUrlDecoder()
                .decode(issued.token().split("\\.")[1]));

        assertThat(payload).contains(user.getUid().toString());
        assertThat(payload)
                .as("a sequential internal id in a token would leak record counts")
                .doesNotContain("\"sub\":\"" + user.getId() + "\"");
    }

    @Test
    @DisplayName("reports the configured lifetime")
    void reportsExpiry() {
        var issued = jwtService.issueAccessToken(user);

        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        assertThat(issued.expiresAt()).isAfter(java.time.Instant.now());
    }

    @Test
    @DisplayName("rejects a token signed with a different key")
    void rejectsForeignSignature() {
        var otherService = new JwtService(
                propertiesWith("a-completely-different-key-also-32-bytes-plus-0123456789", Duration.ofMinutes(15)));
        otherService.initialiseKey();
        String foreignToken = otherService.issueAccessToken(user).token();

        assertThatThrownBy(() -> jwtService.parseAccessToken(foreignToken))
                .isInstanceOf(Exceptions.InvalidToken.class)
                .hasMessageContaining("invalid");
    }

    @Test
    @DisplayName("rejects an expired token")
    void rejectsExpiredToken() {
        // Negative TTL produces a token that expired before it was issued, past
        // the 30-second clock-skew allowance.
        var expiring = new JwtService(propertiesWith(SECRET, Duration.ofSeconds(-120)));
        expiring.initialiseKey();
        String expired = expiring.issueAccessToken(user).token();

        assertThatThrownBy(() -> jwtService.parseAccessToken(expired))
                .isInstanceOf(Exceptions.InvalidToken.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("rejects a token from a different issuer")
    void rejectsForeignIssuer() {
        var otherIssuer = new JwtService(new SecurityProperties(
                new SecurityProperties.Jwt(SECRET, "someone-else", Duration.ofMinutes(15)),
                new SecurityProperties.Refresh(Duration.ofDays(7), 5),
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15), Duration.ofMillis(1)),
                new SecurityProperties.RateLimit(10, false),
                new SecurityProperties.PasswordReset(Duration.ofMinutes(30)),
                new SecurityProperties.Signup(false, Duration.ofHours(24)),
                new SecurityProperties.Cors(List.of("http://localhost:3000"), List.of("GET"), Duration.ofHours(1))));
        otherIssuer.initialiseKey();
        String token = otherIssuer.issueAccessToken(user).token();

        assertThatThrownBy(() -> jwtService.parseAccessToken(token))
                .isInstanceOf(Exceptions.InvalidToken.class);
    }

    @Test
    @DisplayName("rejects structurally malformed input")
    void rejectsMalformedToken() {
        for (String bad : List.of("", "not-a-token", "a.b.c", "....", "Bearer something")) {
            assertThatThrownBy(() -> jwtService.parseAccessToken(bad))
                    .as("input %s must be rejected", bad)
                    .isInstanceOf(Exceptions.InvalidToken.class);
        }
    }

    @Test
    @DisplayName("refuses to start with a secret shorter than HS256 requires")
    void refusesWeakSecret() {
        var weak = new JwtService(propertiesWith("too-short", Duration.ofMinutes(15)));

        assertThatThrownBy(weak::initialiseKey)
                .as("a weak signing key must stop startup, not be silently accepted")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    @DisplayName("a user with no roles yields a token with no authorities")
    void handlesUserWithoutRoles() {
        var bare = new User();
        bare.setEmail("bare@example.com");
        bare.setFullName("Bare User");

        var principal = jwtService.parseAccessToken(jwtService.issueAccessToken(bare).token());

        assertThat(principal.roles()).isEmpty();
        assertThat(principal.permissions()).isEmpty();
    }

    // ------------------------------------------------------------------

    private SecurityProperties propertiesWith(String secret, Duration accessTtl) {
        return new SecurityProperties(
                new SecurityProperties.Jwt(secret, ISSUER, accessTtl),
                new SecurityProperties.Refresh(Duration.ofDays(7), 5),
                new SecurityProperties.Lockout(5, Duration.ofMinutes(15), Duration.ofMillis(1)),
                new SecurityProperties.RateLimit(10, false),
                new SecurityProperties.PasswordReset(Duration.ofMinutes(30)),
                new SecurityProperties.Signup(false, Duration.ofHours(24)),
                new SecurityProperties.Cors(List.of("http://localhost:3000"), List.of("GET"), Duration.ofHours(1)));
    }

    private User userWithPermissions(String email, String roleName, String... permissionNames) {
        var role = new Role();
        role.setName(roleName);
        role.setDisplayName(roleName);
        for (String name : permissionNames) {
            var permission = new Permission();
            permission.setName(name);
            permission.setResource(name.split(":")[0]);
            permission.setAction(name.split(":")[1]);
            role.getPermissions().add(permission);
        }

        var created = new User();
        created.setEmail(email);
        created.setFullName("Test User");
        created.getRoles().add(role);
        return created;
    }
}
