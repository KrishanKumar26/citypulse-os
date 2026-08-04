package com.citypulse.audit.repository;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;

/**
 * Read and insert only. No update or delete method is declared, which keeps the
 * append-only guarantee enforceable by inspection (docs/SECURITY.md §6).
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            SELECT a FROM AuditLog a
             WHERE (:action IS NULL OR a.action = :action)
               AND (:actorId IS NULL OR a.actorId = :actorId)
               AND (CAST(:from AS timestamp) IS NULL OR a.occurredAt >= :from)
               AND (CAST(:to AS timestamp) IS NULL OR a.occurredAt <= :to)
             ORDER BY a.occurredAt DESC
            """)
    Page<AuditLog> search(@Param("action") AuditAction action,
                          @Param("actorId") Long actorId,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}
