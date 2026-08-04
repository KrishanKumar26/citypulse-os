package com.citypulse.security.jwt;

import com.citypulse.common.config.SecurityProperties;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.user.domain.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Issues and verifies access tokens (docs/SECURITY.md §2).
 *
 * <p>Access tokens are short-lived (15 minutes) and stateless — there is no
 * server-side revocation. That is a deliberate trade: statelessness removes a
 * database read from every request, and the short lifetime bounds the damage of
 * a stolen token. Anything requiring immediate revocation goes through the
 * refresh token, which <em>is</em> stateful and revocable.
 *
 * <p>Roles and permissions are embedded as claims so authorization needs no
 * lookup. The cost is that a permission change takes effect at most one access
 * token lifetime later, which is acceptable at 15 minutes.
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private static final String CLAIM_ROLES = "roles";
    private static final String CLAIM_PERMISSIONS = "perms";
    private static final String CLAIM_EMAIL = "email";
    private static final String CLAIM_NAME = "name";
    private static final String CLAIM_TYPE = "typ";
    private static final String TYPE_ACCESS = "access";

    /** HS256 requires a key of at least 256 bits. */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecurityProperties properties;
    private SecretKey signingKey;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void initialiseKey() {
        byte[] secret = properties.jwt().secret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < MIN_SECRET_BYTES) {
            // Fail at startup rather than issuing weakly-signed tokens.
            throw new IllegalStateException(
                    "CITYPULSE_JWT_SECRET must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256; got " + secret.length);
        }
        this.signingKey = Keys.hmacShaKeyFor(secret);
        log.info("JWT signing key initialised (access token TTL: {})", properties.jwt().accessTokenTtl());
    }

    public IssuedToken issueAccessToken(User user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.jwt().accessTokenTtl());

        String token = Jwts.builder()
                // The public uid, never the internal id.
                .subject(user.getUid().toString())
                .issuer(properties.jwt().issuer())
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_EMAIL, user.getEmail())
                .claim(CLAIM_NAME, user.getFullName())
                .claim(CLAIM_ROLES, List.copyOf(user.roleNames()))
                .claim(CLAIM_PERMISSIONS, List.copyOf(user.permissionNames()))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        return new IssuedToken(token, expiry, properties.jwt().accessTokenTtl().toSeconds());
    }

    /**
     * Verifies signature, issuer, expiry, and token type.
     *
     * @throws Exceptions.InvalidToken for every failure mode, with a message that
     *                                 does not disclose which check failed beyond
     *                                 expiry (which clients must distinguish to
     *                                 trigger a refresh)
     */
    public AuthenticatedPrincipal parseAccessToken(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.jwt().issuer())
                    // Tolerates minor clock drift between issuing and verifying hosts.
                    .clockSkewSeconds(30)
                    .build()
                    .parseSignedClaims(token);

            Claims claims = jws.getPayload();
            if (!TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class))) {
                throw new Exceptions.InvalidToken("Token is not an access token");
            }

            return new AuthenticatedPrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get(CLAIM_EMAIL, String.class),
                    claims.get(CLAIM_NAME, String.class),
                    Set.copyOf(stringList(claims, CLAIM_ROLES)),
                    Set.copyOf(stringList(claims, CLAIM_PERMISSIONS))
            );
        } catch (ExpiredJwtException ex) {
            throw new Exceptions.InvalidToken("Access token has expired");
        } catch (JwtException | IllegalArgumentException ex) {
            // Covers bad signature, malformed token, and unparseable subject.
            // The cause is logged but never returned, so a probing client
            // learns nothing about why verification failed.
            log.debug("Rejected access token: {}", ex.getMessage());
            throw new Exceptions.InvalidToken("Access token is invalid");
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> stringList(Claims claims, String name) {
        Object value = claims.get(name);
        return value instanceof List<?> list ? (List<String>) list : List.of();
    }

    public record IssuedToken(String token, Instant expiresAt, long expiresInSeconds) {
    }
}
