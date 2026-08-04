package com.citypulse.auth.repository;

import com.citypulse.auth.domain.UserToken;
import com.citypulse.auth.domain.UserTokenType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;

@Repository
public interface UserTokenRepository extends JpaRepository<UserToken, Long> {

    Optional<UserToken> findByTokenHashAndTokenType(String tokenHash, UserTokenType tokenType);

    /**
     * Invalidates outstanding tokens of a type when a new one is issued, so only
     * the most recent link works.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            UPDATE UserToken t SET t.usedAt = :now
             WHERE t.userId = :userId AND t.tokenType = :type AND t.usedAt IS NULL
            """)
    int invalidateOutstanding(@Param("userId") Long userId,
                              @Param("type") UserTokenType type,
                              @Param("now") Instant now);

    @Modifying
    @Query("DELETE FROM UserToken t WHERE t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") Instant before);
}
