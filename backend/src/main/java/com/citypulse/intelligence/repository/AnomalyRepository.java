package com.citypulse.intelligence.repository;

import com.citypulse.intelligence.domain.Anomaly;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

/**
 * Anomaly reads.
 *
 * <p>Two query methods rather than one with optional predicates. The obvious
 * single query — {@code (:severity IS NULL OR a.severity = :severity)} — fails
 * on PostgreSQL with "could not determine data type of parameter $2": the driver
 * cannot infer a type for a null bind inside an {@code IS NULL} test, and the
 * whole request 500s. Separate methods make the types unambiguous, and read more
 * plainly besides.
 */
public interface AnomalyRepository extends JpaRepository<Anomaly, Long> {

    @EntityGraph(attributePaths = {"zone", "city"})
    @Query("""
            SELECT a FROM Anomaly a
            WHERE a.city.id = :cityId AND a.windowStart >= :since
            ORDER BY a.deviationScore DESC, a.windowStart DESC
            """)
    Page<Anomaly> findSince(@Param("cityId") Long cityId,
                            @Param("since") Instant since,
                            Pageable pageable);

    @EntityGraph(attributePaths = {"zone", "city"})
    @Query("""
            SELECT a FROM Anomaly a
            WHERE a.city.id = :cityId AND a.windowStart >= :since AND a.severity = :severity
            ORDER BY a.deviationScore DESC, a.windowStart DESC
            """)
    Page<Anomaly> findSinceWithSeverity(@Param("cityId") Long cityId,
                                        @Param("since") Instant since,
                                        @Param("severity") String severity,
                                        Pageable pageable);

    @Query("SELECT COUNT(a) FROM Anomaly a WHERE a.city.id = :cityId AND a.windowStart >= :since")
    long countRecent(@Param("cityId") Long cityId, @Param("since") Instant since);
}
