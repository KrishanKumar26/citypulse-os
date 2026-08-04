package com.citypulse.auth.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.domain.AuditOutcome;
import com.citypulse.audit.service.AuditService;
import com.citypulse.common.config.SecurityProperties;
import com.citypulse.user.domain.User;
import com.citypulse.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Tracks consecutive failed sign-in attempts and applies lockout.
 *
 * <p>Separate bean, and {@code REQUIRES_NEW}, for a specific reason: the login
 * transaction rolls back when authentication fails. If the counter were
 * incremented inside it, the increment would roll back too and the account would
 * never lock, no matter how many attempts were made. Committing in an
 * independent transaction is what makes lockout actually work.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    private final UserRepository userRepository;
    private final AuditService auditService;
    private final SecurityProperties securityProperties;

    public LoginAttemptService(UserRepository userRepository,
                               AuditService auditService,
                               SecurityProperties securityProperties) {
        this.userRepository = userRepository;
        this.auditService = auditService;
        this.securityProperties = securityProperties;
    }

    /** Records one failed attempt, locking the account once the threshold is reached. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void registerFailure(Long userId, String email) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId).orElse(null);
        if (user == null) {
            return;
        }

        int maxAttempts = securityProperties.lockout().maxFailedAttempts();
        int attempts = user.getFailedLoginAttempts() + 1;

        if (attempts >= maxAttempts) {
            user.setLockedUntil(Instant.now().plus(securityProperties.lockout().duration()));
            // Reset so the next window starts clean once the lock expires.
            user.setFailedLoginAttempts(0);
            userRepository.save(user);

            auditService.record(AuditAction.ACCOUNT_LOCKED, AuditOutcome.SUCCESS, user.getId(), email,
                    null, null, "Locked for %s after %d failed attempts"
                            .formatted(securityProperties.lockout().duration(), maxAttempts));
            log.warn("Account locked after {} failed sign-in attempts: userId={}", maxAttempts, userId);
        } else {
            user.setFailedLoginAttempts(attempts);
            userRepository.save(user);
        }

        auditService.recordFailure(AuditAction.LOGIN_FAILURE, email,
                "Incorrect password (attempt %d of %d)".formatted(attempts, maxAttempts));
    }

    /** Clears the counter and stamps the successful sign-in. */
    @Transactional
    public void registerSuccess(User user) {
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(Instant.now());
        userRepository.save(user);
    }
}
