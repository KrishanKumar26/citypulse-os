package com.citypulse.alert.repository;

import com.citypulse.alert.domain.Alert;
import com.citypulse.alert.domain.AlertStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByUid(UUID uid);

    /**
     * The open alert for a condition, if one is already raised.
     *
     * <p>The rule engine checks this before inserting so a persistent condition
     * updates its existing alert instead of raising a new one every cycle. The
     * partial unique index is the real guarantee; this is what lets the engine
     * avoid provoking a constraint violation in the normal case.
     */
    @Query("SELECT a FROM Alert a WHERE a.dedupeKey = :key AND a.status <> 'RESOLVED'")
    Optional<Alert> findOpenByDedupeKey(@Param("key") String key);

    @Query("SELECT COUNT(a) FROM Alert a WHERE a.city.id = :cityId AND a.status <> 'RESOLVED'")
    int countOpenForCity(@Param("cityId") Long cityId);

    @Query("""
            SELECT a FROM Alert a
            WHERE (:cityId IS NULL OR a.city.id = :cityId)
              AND (:status IS NULL OR a.status = :status)
              AND (:openOnly = FALSE OR a.status <> 'RESOLVED')
            ORDER BY a.severity DESC, a.raisedAt DESC
            """)
    Page<Alert> search(@Param("cityId") Long cityId,
                       @Param("status") AlertStatus status,
                       @Param("openOnly") boolean openOnly,
                       Pageable pageable);

    /**
     * Open alerts whose triggering window has aged out.
     *
     * <p>Used to auto-resolve: a congestion alert whose zone stopped reporting
     * hours ago is not still true, and leaving it open would slowly fill the
     * Alert Center with conditions nobody can act on.
     */
    @Query("""
            SELECT a FROM Alert a
            WHERE a.status <> 'RESOLVED'
              AND a.zoneMetricWindowStart IS NOT NULL
              AND a.zoneMetricWindowStart < :before
            """)
    List<Alert> findStaleOpen(@Param("before") java.time.Instant before);

    @Query("SELECT a FROM Alert a WHERE a.zone.id = :zoneId AND a.status <> 'RESOLVED' ORDER BY a.raisedAt DESC")
    List<Alert> findOpenForZone(@Param("zoneId") Long zoneId);
}
