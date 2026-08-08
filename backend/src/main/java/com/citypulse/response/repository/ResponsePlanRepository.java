package com.citypulse.response.repository;

import com.citypulse.response.domain.ResponsePlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ResponsePlanRepository extends JpaRepository<ResponsePlan, Long> {

    Optional<ResponsePlan> findByUidAndDeletedAtIsNull(UUID uid);

    /**
     * A city's plans, open ones first.
     *
     * <p>Ordered by status before date because an operations list is read to
     * find what still needs doing, not to browse history — a completed plan from
     * an hour ago is less interesting than a draft from yesterday.
     */
    @Query("""
            SELECT p FROM ResponsePlan p
            WHERE p.city.id = :cityId AND p.deletedAt IS NULL
              AND (:openOnly = FALSE OR p.status IN ('DRAFT', 'ACTIVE'))
            ORDER BY
              CASE p.status WHEN 'ACTIVE' THEN 0 WHEN 'DRAFT' THEN 1 ELSE 2 END,
              CASE p.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                              WHEN 'MEDIUM' THEN 2 ELSE 3 END,
              p.createdAt DESC
            """)
    List<ResponsePlan> findForCity(@Param("cityId") Long cityId,
                                   @Param("openOnly") boolean openOnly);
}
