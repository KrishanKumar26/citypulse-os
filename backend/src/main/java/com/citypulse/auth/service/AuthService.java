package com.citypulse.auth.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.domain.AuditOutcome;
import com.citypulse.audit.service.AuditService;
import com.citypulse.auth.domain.RefreshToken;
import com.citypulse.auth.domain.UserToken;
import com.citypulse.auth.domain.UserTokenType;
import com.citypulse.auth.dto.AuthRequests;
import com.citypulse.auth.dto.AuthResponses;
import com.citypulse.auth.repository.RefreshTokenRepository;
import com.citypulse.auth.repository.UserTokenRepository;
import com.citypulse.common.config.AppProperties;
import com.citypulse.common.config.SecurityProperties;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.notification.EmailSender;
import com.citypulse.security.jwt.JwtService;
import com.citypulse.user.domain.Role;
import com.citypulse.user.domain.RoleName;
import com.citypulse.user.domain.User;
import com.citypulse.user.domain.UserStatus;
import com.citypulse.user.dto.UserMapper;
import com.citypulse.user.repository.RoleRepository;
import com.citypulse.user.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Authentication and session lifecycle (PRD §7, docs/SECURITY.md §2).
 *
 * <p>Two properties are maintained throughout and are covered by tests:
 * <ul>
 *   <li><b>No account enumeration.</b> Login and password reset produce the same
 *       observable result whether or not the address is registered — same status,
 *       same body, and comparable timing.</li>
 *   <li><b>Refresh tokens are single-use.</b> Every exchange consumes the token
 *       and issues a replacement. Re-presenting a consumed token means it was
 *       captured, so the whole family is revoked.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * BCrypt hash of a value no user can hold, compared against when the account
     * does not exist so a login attempt costs the same either way. Without this,
     * response time alone reveals which addresses are registered.
     */
    private static final String DUMMY_HASH =
            "$2a$12$C6UzMDM.H6dfI/f/IKcEe.7Wl2s0PSbCe9YCJ6WrDkjSSJ0P.eIEG";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserTokenRepository userTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenHasher tokenHasher;
    private final AuditService auditService;
    private final EmailSender emailSender;
    private final UserMapper userMapper;
    private final LoginAttemptService loginAttemptService;
    private final TokenRevocationService tokenRevocationService;
    private final SecurityProperties securityProperties;
    private final AppProperties appProperties;

    public AuthService(UserRepository userRepository,
                       RoleRepository roleRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       UserTokenRepository userTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TokenHasher tokenHasher,
                       AuditService auditService,
                       EmailSender emailSender,
                       UserMapper userMapper,
                       LoginAttemptService loginAttemptService,
                       TokenRevocationService tokenRevocationService,
                       SecurityProperties securityProperties,
                       AppProperties appProperties) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userTokenRepository = userTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenHasher = tokenHasher;
        this.auditService = auditService;
        this.emailSender = emailSender;
        this.userMapper = userMapper;
        this.loginAttemptService = loginAttemptService;
        this.tokenRevocationService = tokenRevocationService;
        this.securityProperties = securityProperties;
        this.appProperties = appProperties;
    }

    // ---------------------------------------------------------------------
    // Signup
    // ---------------------------------------------------------------------

    @Transactional
    public AuthResponses.SignupResult signup(AuthRequests.Signup request) {
        String email = normaliseEmail(request.email());

        if (userRepository.existsByEmailAndDeletedAtIsNull(email)) {
            // Signup is the one flow where a duplicate must be reported: the user
            // cannot proceed otherwise. The address is already known to whoever
            // owns it, and the alternative — silently doing nothing — produces a
            // worse and more confusing failure.
            throw new Exceptions.Conflict("An account with this email already exists");
        }

        Role defaultRole = roleRepository.findByNameAndDeletedAtIsNull(RoleName.DEFAULT_SIGNUP_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Default signup role " + RoleName.DEFAULT_SIGNUP_ROLE + " is missing; check migration V2"));

        boolean requireVerification = securityProperties.signup().requireEmailVerification();

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setFullName(request.fullName().trim());
        user.setOrganization(trimToNull(request.organization()));
        // Self-service signup grants VIEWER only. Elevation is an administrator
        // action and is audited (PRD §5).
        user.getRoles().add(defaultRole);
        user.setPasswordChangedAt(Instant.now());
        user.setStatus(requireVerification ? UserStatus.PENDING_VERIFICATION : UserStatus.ACTIVE);
        user.setEmailVerified(!requireVerification);

        User saved = userRepository.save(user);
        auditService.recordSuccess(AuditAction.SIGNUP, saved.getId(), saved.getEmail(),
                "Self-service signup with role " + RoleName.DEFAULT_SIGNUP_ROLE);

        if (requireVerification) {
            issueEmailVerification(saved);
            return new AuthResponses.SignupResult(saved.getEmail(), saved.getStatus().name(), true,
                    emailSender.deliversMail()
                            ? "Account created. Check your email to verify the address before signing in."
                            // Never claim delivery that did not happen.
                            : "Account created. Email delivery is not configured in this environment; "
                              + "the verification link was written to the application log.");
        }

        return new AuthResponses.SignupResult(saved.getEmail(), saved.getStatus().name(), false,
                "Account created. You can now sign in.");
    }

    // ---------------------------------------------------------------------
    // Login
    // ---------------------------------------------------------------------

    @Transactional
    public AuthResponses.Tokens login(AuthRequests.Login request, HttpServletRequest httpRequest) {
        String email = normaliseEmail(request.email());
        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull(email);

        if (found.isEmpty()) {
            // Spend the same time as a real comparison before failing.
            passwordEncoder.matches(request.password(), DUMMY_HASH);
            auditService.recordFailure(AuditAction.LOGIN_FAILURE, email, "No account for address");
            throw new Exceptions.InvalidCredentials();
        }

        User user = found.get();

        if (user.isLocked()) {
            auditService.record(AuditAction.LOGIN_FAILURE, AuditOutcome.DENIED, user.getId(), user.getEmail(),
                    null, null, "Attempt while account locked");
            throw new Exceptions.AccountUnavailable(
                    "Account is temporarily locked after repeated failed sign-in attempts. Try again later.");
        }

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            // Committed in its own transaction; this one is about to roll back.
            loginAttemptService.registerFailure(user.getId(), user.getEmail());
            throw new Exceptions.InvalidCredentials();
        }

        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            auditService.record(AuditAction.LOGIN_FAILURE, AuditOutcome.DENIED, user.getId(), user.getEmail(),
                    null, null, "Email not verified");
            throw new Exceptions.AccountUnavailable("Verify your email address before signing in.");
        }
        if (user.getStatus() == UserStatus.SUSPENDED) {
            auditService.record(AuditAction.LOGIN_FAILURE, AuditOutcome.DENIED, user.getId(), user.getEmail(),
                    null, null, "Account suspended");
            throw new Exceptions.AccountUnavailable("This account has been suspended. Contact an administrator.");
        }

        // The plaintext has just been proven correct and is still in hand — the
        // one moment a stored hash can be moved to the current cost factor.
        upgradeHashIfStale(user, request.password());

        loginAttemptService.registerSuccess(user);
        auditService.recordSuccess(AuditAction.LOGIN_SUCCESS, user.getId(), user.getEmail(), null);

        // A fresh login starts a new token family, so revoking one compromised
        // session does not sign the user out everywhere.
        return issueSession(user, UUID.randomUUID(), httpRequest);
    }


    /**
     * The cost factor prefix the encoder currently writes.
     *
     * A bcrypt string carries its own cost, so a hash made at 12 keeps verifying
     * at 12 no matter what the encoder is configured to produce. Without a
     * re-hash, lowering the strength would speed up new accounts only and leave
     * every existing user on the old, slower path forever.
     */
    private static final String CURRENT_HASH_PREFIX = "$2a$10$";

    /**
     * Moves a password to the current cost factor, once, on a successful login.
     *
     * Costs one extra hash on the first sign-in after a strength change and
     * nothing on any sign-in after that. A hash whose prefix is not recognised
     * is left alone rather than re-hashed on every login forever.
     */
    private void upgradeHashIfStale(User user, String provenPassword) {
        String current = user.getPasswordHash();
        if (current != null && current.startsWith(CURRENT_HASH_PREFIX)) {
            return;
        }
        user.setPasswordHash(passwordEncoder.encode(provenPassword));
        userRepository.save(user);
    }

    // ---------------------------------------------------------------------
    // Refresh
    // ---------------------------------------------------------------------

    @Transactional
    public AuthResponses.Tokens refresh(AuthRequests.Refresh request, HttpServletRequest httpRequest) {
        String hash = tokenHasher.hash(request.refreshToken());
        RefreshToken stored = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> new Exceptions.InvalidToken("Refresh token is invalid"));

        if (stored.isConsumed()) {
            // The token was already exchanged. A second presentation means the
            // value leaked, and every token derived from it is untrusted.
            //
            // Committed in its own transaction: this method throws immediately
            // below, which would otherwise roll the revocation back and leave the
            // stolen token's replacement usable.
            tokenRevocationService.revokeFamily(stored.getFamilyId(), "TOKEN_REUSE_DETECTED");
            auditService.record(AuditAction.TOKEN_REUSE_DETECTED, AuditOutcome.DENIED, stored.getUserId(), null,
                    "REFRESH_TOKEN", stored.getFamilyId().toString(),
                    "Consumed refresh token presented again; token family revoked");
            log.warn("Refresh token reuse detected; revoked family {} for userId={}",
                    stored.getFamilyId(), stored.getUserId());
            throw new Exceptions.InvalidToken("Refresh token is invalid");
        }

        if (!stored.isUsable()) {
            throw new Exceptions.InvalidToken("Refresh token has expired or been revoked");
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(stored.getUserId())
                .orElseThrow(() -> new Exceptions.InvalidToken("Refresh token is invalid"));

        if (!user.canAuthenticate()) {
            // Same reasoning: must outlive the throw on the next line.
            tokenRevocationService.revokeAllForUser(user.getId(), "ACCOUNT_UNAVAILABLE");
            throw new Exceptions.AccountUnavailable("This account can no longer be used to sign in.");
        }

        stored.setConsumedAt(Instant.now());
        refreshTokenRepository.save(stored);

        auditService.recordSuccess(AuditAction.TOKEN_REFRESH, user.getId(), user.getEmail(), null);

        // Rotation keeps the family so reuse detection still covers the chain.
        return issueSession(user, stored.getFamilyId(), httpRequest);
    }

    // ---------------------------------------------------------------------
    // Logout
    // ---------------------------------------------------------------------

    /**
     * Revokes the presented token's entire family, ending that session on every
     * device it was rotated onto.
     *
     * <p>Always reports success. An unknown token means the session is already
     * unusable, which is the outcome the caller asked for, and reporting
     * otherwise would let an unauthenticated caller test token validity.
     */
    @Transactional
    public void logout(AuthRequests.Logout request) {
        String hash = tokenHasher.hash(request.refreshToken());
        refreshTokenRepository.findByTokenHash(hash).ifPresent(stored -> {
            tokenRevocationService.revokeFamily(stored.getFamilyId(), "LOGOUT");
            userRepository.findByIdAndDeletedAtIsNull(stored.getUserId()).ifPresent(user ->
                    auditService.recordSuccess(AuditAction.LOGOUT, user.getId(), user.getEmail(), null));
        });
    }

    // ---------------------------------------------------------------------
    // Email verification
    // ---------------------------------------------------------------------

    @Transactional
    public void verifyEmail(String token) {
        UserToken stored = userTokenRepository
                .findByTokenHashAndTokenType(tokenHasher.hash(token), UserTokenType.EMAIL_VERIFICATION)
                .orElseThrow(() -> new Exceptions.InvalidToken("Verification link is invalid or has expired"));

        if (!stored.isUsable()) {
            throw new Exceptions.InvalidToken("Verification link is invalid or has expired");
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(stored.getUserId())
                .orElseThrow(() -> new Exceptions.InvalidToken("Verification link is invalid or has expired"));

        stored.setUsedAt(Instant.now());
        userTokenRepository.save(stored);

        user.setEmailVerified(true);
        if (user.getStatus() == UserStatus.PENDING_VERIFICATION) {
            user.setStatus(UserStatus.ACTIVE);
        }
        userRepository.save(user);

        auditService.recordSuccess(AuditAction.EMAIL_VERIFICATION, user.getId(), user.getEmail(), null);
    }

    private void issueEmailVerification(User user) {
        String raw = tokenHasher.generateToken();
        userTokenRepository.invalidateOutstanding(user.getId(), UserTokenType.EMAIL_VERIFICATION, Instant.now());

        UserToken token = new UserToken();
        token.setUserId(user.getId());
        token.setTokenType(UserTokenType.EMAIL_VERIFICATION);
        token.setTokenHash(tokenHasher.hash(raw));
        token.setExpiresAt(Instant.now().plus(securityProperties.signup().verificationTokenTtl()));
        userTokenRepository.save(token);

        emailSender.sendEmailVerification(user.getEmail(), user.getFullName(),
                appProperties.buildLink("/verify-email", raw));
    }

    // ---------------------------------------------------------------------
    // Password reset
    // ---------------------------------------------------------------------

    /**
     * Always completes normally, whether or not the address is registered
     * (docs/SECURITY.md §2). The work done differs, but nothing observable to the
     * caller does.
     */
    @Transactional
    public void requestPasswordReset(AuthRequests.ForgotPassword request) {
        String email = normaliseEmail(request.email());
        Optional<User> found = userRepository.findByEmailAndDeletedAtIsNull(email);

        if (found.isEmpty()) {
            log.debug("Password reset requested for unregistered address");
            auditService.recordFailure(AuditAction.PASSWORD_RESET_REQUEST, email, "No account for address");
            return;
        }

        User user = found.get();
        String raw = tokenHasher.generateToken();
        userTokenRepository.invalidateOutstanding(user.getId(), UserTokenType.PASSWORD_RESET, Instant.now());

        UserToken token = new UserToken();
        token.setUserId(user.getId());
        token.setTokenType(UserTokenType.PASSWORD_RESET);
        token.setTokenHash(tokenHasher.hash(raw));
        token.setExpiresAt(Instant.now().plus(securityProperties.passwordReset().tokenTtl()));
        userTokenRepository.save(token);

        emailSender.sendPasswordReset(user.getEmail(), user.getFullName(),
                appProperties.buildLink("/reset-password", raw));

        auditService.recordSuccess(AuditAction.PASSWORD_RESET_REQUEST, user.getId(), user.getEmail(), null);
    }

    @Transactional
    public void resetPassword(AuthRequests.ResetPassword request) {
        UserToken stored = userTokenRepository
                .findByTokenHashAndTokenType(tokenHasher.hash(request.token()), UserTokenType.PASSWORD_RESET)
                .orElseThrow(() -> new Exceptions.InvalidToken("Reset link is invalid or has expired"));

        if (!stored.isUsable()) {
            throw new Exceptions.InvalidToken("Reset link is invalid or has expired");
        }

        User user = userRepository.findByIdAndDeletedAtIsNull(stored.getUserId())
                .orElseThrow(() -> new Exceptions.InvalidToken("Reset link is invalid or has expired"));

        stored.setUsedAt(Instant.now());
        userTokenRepository.save(stored);

        applyNewPassword(user, request.newPassword());

        auditService.recordSuccess(AuditAction.PASSWORD_RESET_COMPLETE, user.getId(), user.getEmail(),
                "Password reset completed; all sessions revoked");
    }

    @Transactional
    public void changePassword(UUID userUid, AuthRequests.ChangePassword request) {
        User user = userRepository.findByUidAndDeletedAtIsNull(userUid)
                .orElseThrow(() -> new Exceptions.NotFound("User", userUid));

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {
            auditService.record(AuditAction.PASSWORD_CHANGE, AuditOutcome.FAILURE, user.getId(), user.getEmail(),
                    null, null, "Current password did not match");
            throw new Exceptions.BadRequest("INVALID_CURRENT_PASSWORD", "Current password is incorrect");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new Exceptions.BadRequest("PASSWORD_UNCHANGED",
                    "New password must be different from the current password");
        }

        applyNewPassword(user, request.newPassword());

        auditService.recordSuccess(AuditAction.PASSWORD_CHANGE, user.getId(), user.getEmail(),
                "Password changed; all sessions revoked");
    }

    /**
     * A password change ends every existing session. If the change was prompted by
     * a suspected compromise, leaving old refresh tokens usable would defeat it.
     */
    private void applyNewPassword(User user, String newPassword) {
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setPasswordChangedAt(Instant.now());
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        tokenRevocationService.revokeAllForUser(user.getId(), "PASSWORD_CHANGED");
    }

    // ---------------------------------------------------------------------
    // Session issuing
    // ---------------------------------------------------------------------

    private AuthResponses.Tokens issueSession(User user, UUID familyId, HttpServletRequest httpRequest) {
        JwtService.IssuedToken access = jwtService.issueAccessToken(user);

        String rawRefresh = tokenHasher.generateToken();
        RefreshToken refreshToken = new RefreshToken();
        refreshToken.setUserId(user.getId());
        refreshToken.setTokenHash(tokenHasher.hash(rawRefresh));
        refreshToken.setFamilyId(familyId);
        refreshToken.setExpiresAt(Instant.now().plus(securityProperties.refresh().ttl()));
        if (httpRequest != null) {
            refreshToken.setClientIp(clientIp(httpRequest));
            refreshToken.setUserAgent(truncate(httpRequest.getHeader("User-Agent")));
        }
        refreshTokenRepository.save(refreshToken);

        enforceSessionLimit(user.getId());

        return AuthResponses.Tokens.bearer(
                access.token(), rawRefresh, access.expiresInSeconds(), access.expiresAt(),
                userMapper.toProfile(user));
    }

    /**
     * Caps concurrently usable sessions, revoking the oldest first. Bounds how
     * many devices a stolen credential can keep alive and stops the token table
     * growing without limit for a single account.
     */
    private void enforceSessionLimit(Long userId) {
        int max = securityProperties.refresh().maxActiveSessionsPerUser();
        List<RefreshToken> active = refreshTokenRepository.findActiveForUser(userId, Instant.now());
        if (active.size() <= max) {
            return;
        }
        active.subList(0, active.size() - max).forEach(token -> {
            token.setRevokedAt(Instant.now());
            token.setRevokedReason("SESSION_LIMIT");
            refreshTokenRepository.save(token);
        });
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Lower-cased and trimmed, matching the {@code ck_users_email_lowercase} constraint. */
    private String normaliseEmail(String email) {
        return email.trim().toLowerCase();
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            return first.length() > 45 ? first.substring(0, 45) : first;
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
