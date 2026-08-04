package com.citypulse.user.repository;

import com.citypulse.user.domain.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Every finder excludes soft-deleted rows. There is no unfiltered {@code findAll}
 * exposed to services, so a deleted account cannot resurface by accident.
 *
 * <p>{@code @EntityGraph} on the lookups that feed authentication loads roles and
 * their permissions in one query — without it, building the authorities for a
 * login would issue a query per role (PRD §44, no N+1).
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<User> findByEmailAndDeletedAtIsNull(String email);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<User> findByUidAndDeletedAtIsNull(UUID uid);

    @EntityGraph(attributePaths = {"roles", "roles.permissions"})
    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    @EntityGraph(attributePaths = {"roles"})
    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND (:search IS NULL
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<User> search(@Param("search") String search, Pageable pageable);
}
