package com.citypulse.apikey.repository;

import com.citypulse.apikey.domain.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /**
     * The lookup on every request that presents a key.
     *
     * <p>By hash, never by prefix: the prefix is public, and matching on it
     * would let anyone who has seen a key in a log narrow the search.
     */
    @Query("SELECT k FROM ApiKey k WHERE k.keyHash = :hash AND k.deletedAt IS NULL")
    Optional<ApiKey> findByHash(@Param("hash") String hash);

    Optional<ApiKey> findByUidAndDeletedAtIsNull(UUID uid);

    @Query("""
            SELECT k FROM ApiKey k
            WHERE k.owner.id = :ownerId AND k.deletedAt IS NULL
            ORDER BY k.revokedAt NULLS FIRST, k.createdAt DESC
            """)
    List<ApiKey> findForOwner(@Param("ownerId") Long ownerId);
}
