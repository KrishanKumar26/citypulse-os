package com.citypulse.geo.repository;

import com.citypulse.geo.domain.Zone;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ZoneRepository extends JpaRepository<Zone, Long> {

    /** City is fetched eagerly here because the zone DTO includes its city slug. */
    @EntityGraph(attributePaths = {"city"})
    Optional<Zone> findByUidAndDeletedAtIsNull(UUID uid);

    @EntityGraph(attributePaths = {"city"})
    @Query("""
            SELECT z FROM Zone z
             WHERE z.city.id = :cityId
               AND z.deletedAt IS NULL
               AND (:activeOnly = FALSE OR z.active = TRUE)
             ORDER BY z.name ASC
            """)
    List<Zone> findByCity(@Param("cityId") Long cityId, @Param("activeOnly") boolean activeOnly);

    /**
     * {@code :search} is cast because it is nullable. Without the cast
     * PostgreSQL has no value from which to infer the parameter's type, falls
     * back to {@code bytea} for the untyped null, and then fails to find
     * {@code lower(bytea)} — so browsing a city's zones without a search term
     * returned 500 while searching for one worked.
     */
    @EntityGraph(attributePaths = {"city"})
    @Query("""
            SELECT z FROM Zone z
             WHERE z.city.id = :cityId
               AND z.deletedAt IS NULL
               AND (CAST(:search AS string) IS NULL
                    OR LOWER(z.name) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%'))
                    OR LOWER(z.code) LIKE LOWER(CONCAT('%', CAST(:search AS string), '%')))
            """)
    Page<Zone> search(@Param("cityId") Long cityId, @Param("search") String search, Pageable pageable);

    /**
     * Every active zone across every active city.
     *
     * <p>For platform-wide sweeps such as alert evaluation, which has no city to
     * scope by. The city is fetched eagerly because callers attribute results to
     * it, and a lazy load here would issue one query per zone.
     */
    @EntityGraph(attributePaths = {"city"})
    @Query("""
            SELECT z FROM Zone z
             WHERE z.deletedAt IS NULL
               AND z.active = TRUE
               AND z.city.deletedAt IS NULL
               AND z.city.active = TRUE
             ORDER BY z.city.slug ASC, z.code ASC
            """)
    List<Zone> findAllActive();

    boolean existsByCityIdAndCodeAndDeletedAtIsNull(Long cityId, String code);

    long countByCityIdAndDeletedAtIsNull(Long cityId);
}
