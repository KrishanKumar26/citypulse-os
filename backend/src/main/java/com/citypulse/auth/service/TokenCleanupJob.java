package com.citypulse.auth.service;

import com.citypulse.auth.repository.RefreshTokenRepository;
import com.citypulse.auth.repository.UserTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;

/**
 * Purges expired tokens. Without this the token tables grow without bound, and
 * the index scans that make refresh fast degrade over time.
 *
 * <p>A grace period is kept after expiry so that reuse detection still has a row
 * to find: a token presented shortly after it expires should be recognised as
 * expired, not as unknown.
 */
@Component
public class TokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(TokenCleanupJob.class);
    private static final Duration RETENTION_AFTER_EXPIRY = Duration.ofDays(3);

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserTokenRepository userTokenRepository;

    public TokenCleanupJob(RefreshTokenRepository refreshTokenRepository,
                           UserTokenRepository userTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userTokenRepository = userTokenRepository;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpiredTokens() {
        Instant cutoff = Instant.now().minus(RETENTION_AFTER_EXPIRY);
        int refreshDeleted = refreshTokenRepository.deleteExpiredBefore(cutoff);
        int userTokensDeleted = userTokenRepository.deleteExpiredBefore(cutoff);
        log.info("Token cleanup removed {} refresh tokens and {} single-use tokens expired before {}",
                refreshDeleted, userTokensDeleted, cutoff);
    }
}
