package com.citypulse.auth.service;

import com.citypulse.auth.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Revocations that must survive the failure that triggered them.
 *
 * <p>Separate bean, and {@code REQUIRES_NEW}, for the same reason as
 * {@link LoginAttemptService}: reuse detection revokes a token family and then
 * throws to reject the request. If the revocation ran in the caller's
 * transaction, the throw would roll it back — the attacker would be told "no"
 * while the stolen token's replacement stayed valid, which is precisely the
 * attack the mechanism exists to stop.
 *
 * <p>Verified by {@code RefreshTokenReuseIT}, which asserts the rotated token is
 * dead after reuse is detected.
 */
@Service
public class TokenRevocationService {

    private static final Logger log = LoggerFactory.getLogger(TokenRevocationService.class);

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenRevocationService(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId, String reason) {
        int revoked = refreshTokenRepository.revokeFamily(familyId, reason, Instant.now());
        log.warn("Revoked {} refresh token(s) in family {} (reason: {})", revoked, familyId, reason);
        return revoked;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(Long userId, String reason) {
        int revoked = refreshTokenRepository.revokeAllForUser(userId, reason, Instant.now());
        log.info("Revoked {} refresh token(s) for userId={} (reason: {})", revoked, userId, reason);
        return revoked;
    }
}
