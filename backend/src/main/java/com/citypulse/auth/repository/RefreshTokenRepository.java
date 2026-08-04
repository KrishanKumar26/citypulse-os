package com.citypulse.auth.repository;

import com.citypulse.auth.domain.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Revokes an entire token family. Called when a consumed token is presented
     * again, which means the token was captured — every descendant is untrusted
     * (docs/SECURITY.md §2).
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.revokedAt = :now, t.revokedReason = :reason
             WHERE t.familyId = :familyId
               AND t.revokedAt IS NULL
            """)
    int revokeFamily(@Param("familyId") UUID familyId,
                     @Param("reason") String reason,
                     @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE RefreshToken t
               SET t.revokedAt = :now, t.revokedReason = :reason
             WHERE t.userId = :userId
               AND t.revokedAt IS NULL
            """)
    int revokeAllForUser(@Param("userId") Long userId,
                         @Param("reason") String reason,
                         @Param("now") Instant now);

    @Query("""
            SELECT t FROM RefreshToken t
             WHERE t.userId = :userId
               AND t.revokedAt IS NULL
               AND t.consumedAt IS NULL
               AND t.expiresAt > :now
             ORDER BY t.createdAt ASC
            """)
    List<RefreshToken> findActiveForUser(@Param("userId") Long userId, @Param("now") Instant now);

    /** Purge for the scheduled cleanup job; expired rows have no further use. */
    @Modifying
    @Query("DELETE FROM RefreshToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
