package com.citypulse.forecast.repository;

import com.citypulse.forecast.domain.ModelRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ModelRunRepository extends JpaRepository<ModelRun, Long> {

    @Query("SELECT r FROM ModelRun r WHERE r.modelName = :name AND r.status = 'ACTIVE'")
    Optional<ModelRun> findActive(@Param("name") String modelName);
}
