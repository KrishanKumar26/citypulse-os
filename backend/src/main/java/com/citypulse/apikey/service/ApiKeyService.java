package com.citypulse.apikey.service;

import com.citypulse.apikey.domain.ApiKey;
import com.citypulse.apikey.dto.ApiKeyDtos;
import com.citypulse.apikey.repository.ApiKeyRepository;
import com.citypulse.auth.service.TokenHasher;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.common.time.Timestamps;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.domain.User;
import com.citypulse.user.repository.UserRepository;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Issuing, listing and revoking API keys (PRD §22).
 *
 * <p>Two rules carry the security of the whole feature.
 *
 * <p><b>The secret is returned once and stored never.</b> Only a SHA-256 of it
 * is kept, so a leaked database yields hashes rather than credentials, and there
 * is nothing anywhere that can show a key again. A key that could be read back
 * would make the database exactly as sensitive as the keys it holds.
 *
 * <p><b>A key cannot carry authority its creator does not hold.</b> Without that
 * check, any user who may create a key could mint one with {@code system:manage}
 * and use it to escalate — the API-key endpoint would become a hole straight
 * through the permission model. Scopes are also frozen at issue rather than
 * resolved from the owner's roles, so a key does not silently widen when someone
 * edits a role months later.
 */
@Service
public class ApiKeyService {

    /**
     * Marks the key in logs and support conversations without revealing it.
     *
     * <p>`live` rather than a random word: a future `cp_test_` would be visibly
     * a different thing at a glance, which is the point of a prefix scheme.
     */
    private static final String PREFIX = "cp_live_";

    /** Enough of the secret to identify it, far too little to guess the rest. */
    private static final int PREFIX_SAMPLE = 8;

    private final ApiKeyRepository repository;
    private final UserRepository userRepository;
    private final TokenHasher hasher;
    private final CurrentUser currentUser;

    public ApiKeyService(ApiKeyRepository repository,
                         UserRepository userRepository,
                         TokenHasher hasher,
                         CurrentUser currentUser) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.hasher = hasher;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ApiKeyDtos.Summary> listMine() {
        return repository.findForOwner(caller().getId()).stream().map(this::toSummary).toList();
    }

    /** What the caller may grant — which is exactly what the caller holds. */
    @Transactional(readOnly = true)
    public ApiKeyDtos.ScopeCatalogue grantableScopes() {
        return new ApiKeyDtos.ScopeCatalogue(new TreeSet<>(callerAuthorities()).stream().toList());
    }

    @Transactional
    public ApiKeyDtos.Created create(ApiKeyDtos.Create request) {
        User owner = caller();
        Set<String> held = callerAuthorities();

        Set<String> requested = request.scopes().stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));

        if (requested.isEmpty()) {
            throw new Exceptions.BadRequest("At least one scope is required");
        }

        // The escalation guard. Without it this endpoint is a hole straight
        // through the permission model: anyone able to create a key could mint
        // one carrying permissions they were never granted.
        Set<String> exceeded = requested.stream().filter(s -> !held.contains(s))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!exceeded.isEmpty()) {
            throw new Exceptions.BadRequest(
                    "A key cannot carry permissions you do not hold: " + String.join(", ", exceeded));
        }

        String secret = PREFIX + hasher.generateToken();

        ApiKey key = new ApiKey();
        key.setName(request.name());
        key.setDescription(request.description());
        key.setOwner(owner);
        key.setScopes(String.join(" ", requested));
        // Stored, so the key can be named later. Not part of the hash, and not
        // a credential.
        key.setKeyPrefix(secret.substring(0, PREFIX.length() + PREFIX_SAMPLE));
        key.setKeyHash(hasher.hash(secret));

        if (request.expiresInDays() != null) {
            if (request.expiresInDays() < 1) {
                throw new Exceptions.BadRequest("'expiresInDays' must be at least 1");
            }
            key.setExpiresAt(Timestamps.now().plus(Duration.ofDays(request.expiresInDays())));
        }

        ApiKey saved = repository.save(key);

        // The only time the secret leaves this method. Nothing stores it, and
        // no endpoint can return it again.
        return new ApiKeyDtos.Created(toSummary(saved), secret);
    }

    @Transactional
    public ApiKeyDtos.Summary revoke(UUID keyId, ApiKeyDtos.Revoke request) {
        ApiKey key = repository.findByUidAndDeletedAtIsNull(keyId)
                .orElseThrow(() -> new Exceptions.NotFound("API key", keyId));

        if (!key.getOwner().getId().equals(caller().getId())) {
            // Reported as not found, not as forbidden. Confirming that a key id
            // exists but belongs to someone else is itself a disclosure.
            throw new Exceptions.NotFound("API key", keyId);
        }

        if (key.getRevokedAt() == null) {
            // Revocation is a timestamp, not a delete: a key that acted has to
            // stay explicable afterwards, and the audit log refers to it.
            key.setRevokedAt(Timestamps.now());
            key.setRevokedReason(request == null ? null : request.reason());
            repository.save(key);
        }
        return toSummary(key);
    }

    /**
     * Everything the authentication filter needs, materialised.
     *
     * <p>A record rather than the entity, because the filter runs outside this
     * transaction: handing it a managed {@code ApiKey} means the first call to
     * {@code getOwner()} throws LazyInitializationException, which it did.
     * Resolving here keeps the session boundary where it belongs.
     */
    public record Resolved(UUID ownerUid, String ownerEmail, String ownerName, Set<String> scopes) {
    }

    /**
     * Resolves a presented key, or nothing.
     *
     * <p>Returns empty for a key that is unknown, revoked, expired or deleted —
     * the caller is told none of which, because distinguishing them tells an
     * attacker whether a guess was once real.
     */
    @Transactional
    public java.util.Optional<Resolved> resolve(String presented, String remoteIp) {
        if (presented == null || !presented.startsWith(PREFIX)) {
            return java.util.Optional.empty();
        }
        Instant now = Timestamps.now();
        return repository.findByHash(hasher.hash(presented))
                .filter(key -> key.isActive(now))
                .map(key -> {
                    // Coarse on purpose. Writing this on every request would put
                    // a database write on the hot path of every authenticated
                    // call; a minute's resolution is enough to find a key nobody
                    // uses any more.
                    if (key.getLastUsedAt() == null
                            || Duration.between(key.getLastUsedAt(), now).toMinutes() >= 1) {
                        key.setLastUsedAt(now);
                        key.setLastUsedIp(remoteIp);
                        repository.save(key);
                    }
                    return new Resolved(
                            key.getOwner().getUid(),
                            key.getOwner().getEmail(),
                            key.getOwner().getFullName(),
                            key.scopeSet());
                });
    }

    private User caller() {
        return userRepository.findByUidAndDeletedAtIsNull(currentUser.require().userUid())
                .orElseThrow(() -> new Exceptions.NotFound("User", currentUser.require().userUid()));
    }

    private Set<String> callerAuthorities() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private ApiKeyDtos.Summary toSummary(ApiKey key) {
        Instant now = Timestamps.now();
        boolean active = key.isActive(now);
        String reason = active ? null
                : key.getRevokedAt() != null ? "Revoked"
                : key.getExpiresAt() != null && !key.getExpiresAt().isAfter(now) ? "Expired"
                : "Deleted";

        return new ApiKeyDtos.Summary(
                key.getUid().toString(),
                key.getName(),
                key.getDescription(),
                key.getKeyPrefix(),
                key.scopeSet(),
                key.getOwner().getEmail(),
                key.getCreatedAt(),
                key.getExpiresAt(),
                key.getRevokedAt(),
                key.getRevokedReason(),
                key.getLastUsedAt(),
                active,
                reason);
    }
}
