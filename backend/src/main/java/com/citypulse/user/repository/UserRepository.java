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

    /**
     * Deliberately carries no {@code @EntityGraph}. A graph over the {@code roles}
     * collection becomes a fetch join, and Hibernate refuses to paginate one:
     * the join multiplies rows per user, so {@code LIMIT} would cut a user in
     * half rather than cut the list. It fails closed —
     * {@code fail_on_pagination_over_collection_fetch} throws instead of quietly
     * paging in memory — so every call to this method returned 500.
     *
     * <p>Nothing is lost by leaving it off: {@code User.roles} is already
     * {@code FetchType.EAGER}, so {@link com.citypulse.user.dto.UserMapper#toSummary}
     * still gets the role names it needs.
     *
     * <p>{@code :search} is cast explicitly because it is nullable. PostgreSQL
     * is asked to type a parameter it has never seen a value for, defaults the
     * untyped null to {@code bytea}, and then cannot find
     * {@code lower(bytea)} — so an unfiltered listing failed while a search for
     * a term succeeded.
     */
    @Query("""
            SELECT u FROM User u
            WHERE u.deletedAt IS NULL
              AND (CAST(:search AS string) IS NULL
                   OR LOWER(u.email) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                   OR LOWER(u.fullName) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<User> search(@Param("search") String search, Pageable pageable);
}
