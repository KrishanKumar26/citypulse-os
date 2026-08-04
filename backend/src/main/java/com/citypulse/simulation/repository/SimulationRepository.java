package com.citypulse.simulation.repository;

import com.citypulse.simulation.domain.Simulation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface SimulationRepository extends JpaRepository<Simulation, Long> {

    /** Results and zones fetched together; a detail view always needs them. */
    @EntityGraph(attributePaths = {"results", "results.zone", "city"})
    Optional<Simulation> findByUid(UUID uid);

    @Query("SELECT s FROM Simulation s WHERE s.city.id = :cityId ORDER BY s.createdAt DESC")
    Page<Simulation> findForCity(@Param("cityId") Long cityId, Pageable pageable);
}
