package com.citypulse.intelligence.repository;

import com.citypulse.intelligence.domain.ConditionCorrelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CorrelationRepository extends JpaRepository<ConditionCorrelation, Long> {

    /**
     * Measured pairings for a city, strongest first.
     *
     * <p>Only those above a lift of 1 are worth showing: at or below it the
     * conditions are independent or negatively associated, which is a finding
     * but not one the Insights page is asking for.
     */
    @Query("""
            SELECT c FROM ConditionCorrelation c
            WHERE c.cityId = :cityId AND c.lift > 1.0
            ORDER BY c.lift DESC
            """)
    List<ConditionCorrelation> findForCity(@Param("cityId") Long cityId);
}
