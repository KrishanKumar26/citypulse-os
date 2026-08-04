package com.citypulse.geo.repository;

import com.citypulse.geo.domain.City;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CityRepository extends JpaRepository<City, Long> {

    Optional<City> findByUidAndDeletedAtIsNull(UUID uid);

    Optional<City> findBySlugAndDeletedAtIsNull(String slug);

    boolean existsBySlugAndDeletedAtIsNull(String slug);

    List<City> findByDeletedAtIsNullOrderByNameAsc();

    List<City> findByActiveTrueAndDeletedAtIsNullOrderByNameAsc();

    /**
     * Zone counts for a set of cities in one query. Fetching each city's zone
     * collection to call {@code size()} would be an N+1 across the city list
     * (PRD §44).
     */
    @Query("""
            SELECT z.city.id, COUNT(z)
              FROM Zone z
             WHERE z.city.id IN :cityIds
               AND z.deletedAt IS NULL
               AND z.active = TRUE
             GROUP BY z.city.id
            """)
    List<Object[]> countActiveZonesByCityIds(@Param("cityIds") List<Long> cityIds);
}
