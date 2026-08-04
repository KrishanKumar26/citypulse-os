package com.citypulse.forecast.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * A training run and the holdout it was measured on (PRD §11).
 *
 * <p>The evaluation window is stored, not just the error, because "we evaluated
 * it" is not a claim anyone should accept without knowing on what. A model
 * tested on data it trained on looks excellent and forecasts nothing, and the
 * only way a reader can rule that out is by seeing that the evaluation period
 * starts where training ended.
 */
@Entity
@Table(name = "model_runs")
@Getter
public class ModelRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, unique = true, updatable = false)
    private UUID uid;

    @Column(name = "model_name", nullable = false)
    private String modelName;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "trained_from", nullable = false)
    private Instant trainedFrom;

    @Column(name = "trained_to", nullable = false)
    private Instant trainedTo;

    @Column(name = "evaluated_from", nullable = false)
    private Instant evaluatedFrom;

    @Column(name = "evaluated_to", nullable = false)
    private Instant evaluatedTo;

    @Column(name = "training_rows", nullable = false)
    private Integer trainingRows;

    @Column(name = "evaluation_rows", nullable = false)
    private Integer evaluationRows;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ModelRun() {
        // JPA.
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
