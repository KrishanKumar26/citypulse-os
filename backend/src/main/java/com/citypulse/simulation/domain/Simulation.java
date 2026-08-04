package com.citypulse.simulation.domain;

import com.citypulse.common.time.Timestamps;
import com.citypulse.geo.domain.City;
import com.citypulse.user.domain.User;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A stored counterfactual (PRD §14).
 *
 * <p>{@code baselineWindow} and {@code engineVersion} are what keep a saved
 * result interpretable. Without the first, "traffic +43%" is a percentage of
 * nothing in particular once conditions move on; without the second, a result
 * read months later cannot say which set of assumptions produced it.
 */
@Entity
@Table(name = "simulations")
@Getter
@Setter
public class Simulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uid", nullable = false, unique = true, updatable = false)
    private UUID uid = UUID.randomUUID();

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    /** The scenario verbatim, so a run can be reproduced and a form repopulated. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scenario", nullable = false)
    private String scenario;

    @Column(name = "baseline_window", nullable = false)
    private Instant baselineWindow;

    @Column(name = "engine_version", nullable = false)
    private String engineVersion;

    @Column(name = "status", nullable = false)
    private String status = "COMPLETED";

    @Column(name = "error_detail")
    private String errorDetail;

    @Column(name = "traffic_change_pct")
    private BigDecimal trafficChangePct;

    @Column(name = "crowd_change_pct")
    private BigDecimal crowdChangePct;

    @Column(name = "parking_change_pct")
    private BigDecimal parkingChangePct;

    @Column(name = "delay_change_min")
    private BigDecimal delayChangeMin;

    @Column(name = "baseline_risk")
    private BigDecimal baselineRisk;

    @Column(name = "simulated_risk")
    private BigDecimal simulatedRisk;

    @Column(name = "zones_affected", nullable = false)
    private Integer zonesAffected = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "recommendations", nullable = false)
    private String recommendations = "[]";

    @Column(name = "computed_ms")
    private Integer computedMs;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Timestamps.now();

    @OneToMany(mappedBy = "simulation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SimulationResult> results = new ArrayList<>();
}
