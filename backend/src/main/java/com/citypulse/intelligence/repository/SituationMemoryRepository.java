package com.citypulse.intelligence.repository;

import com.citypulse.intelligence.domain.SituationMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SituationMemoryRepository extends JpaRepository<SituationMemory, Long> {

    /**
     * Past situations matching a fingerprint (PRD §16).
     *
     * <p>Matched on the coarse bands rather than exact values, because the
     * question is "has this kind of thing happened" and exact matching would
     * return nothing. Ordered by recency: a similar situation last week is more
     * informative about the city as it is now than one from a month ago.
     *
     * <p>The zone is deliberately not part of the match. A rainy Friday evening
     * with an event behaves similarly across comparable zones, and restricting
     * to one zone would usually leave too few examples to say anything.
     */
    @Query("""
            SELECT s FROM SituationMemory s
            WHERE s.zone.city.id = :cityId
              AND s.rainBand = :rainBand
              AND s.dayType = :dayType
              AND s.hourBand = :hourBand
              AND s.hadEvent = :hadEvent
              AND s.incidentBand = :incidentBand
            ORDER BY s.occurredAt DESC
            """)
    List<SituationMemory> findSimilar(@Param("cityId") Long cityId,
                                      @Param("rainBand") String rainBand,
                                      @Param("dayType") String dayType,
                                      @Param("hourBand") String hourBand,
                                      @Param("hadEvent") boolean hadEvent,
                                      @Param("incidentBand") String incidentBand);

    /**
     * A looser match, used when the exact fingerprint returns too few examples.
     *
     * <p>Drops the incident band, which is the least defining of the five: a
     * rainy Friday evening with an event is recognisably the same situation
     * whether or not a fender-bender is open somewhere in it.
     */
    @Query("""
            SELECT s FROM SituationMemory s
            WHERE s.zone.city.id = :cityId
              AND s.rainBand = :rainBand
              AND s.dayType = :dayType
              AND s.hourBand = :hourBand
              AND s.hadEvent = :hadEvent
            ORDER BY s.occurredAt DESC
            """)
    List<SituationMemory> findSimilarRelaxed(@Param("cityId") Long cityId,
                                             @Param("rainBand") String rainBand,
                                             @Param("dayType") String dayType,
                                             @Param("hourBand") String hourBand,
                                             @Param("hadEvent") boolean hadEvent);
}
