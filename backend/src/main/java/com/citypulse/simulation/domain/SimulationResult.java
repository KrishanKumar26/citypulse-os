package com.citypulse.simulation.domain;

import com.citypulse.geo.domain.Zone;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * One zone's before and after.
 *
 * <p>Both sides are stored rather than only the delta: a +43% change from 0.3
 * and from 0.9 are entirely different situations, and only one of them is a
 * problem.
 */
@Entity
@Table(name = "simulation_results")
@Getter
@Setter
public class SimulationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "simulation_id", nullable = false)
    private Simulation simulation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id", nullable = false)
    private Zone zone;

    @Column(name = "baseline_occupancy")      private BigDecimal baselineOccupancy;
    @Column(name = "baseline_speed_kph")      private BigDecimal baselineSpeedKph;
    @Column(name = "baseline_vehicle_count")  private Integer baselineVehicleCount;
    @Column(name = "baseline_risk_score")     private BigDecimal baselineRiskScore;
    @Column(name = "baseline_congestion")     private String baselineCongestion;

    @Column(name = "simulated_occupancy")     private BigDecimal simulatedOccupancy;
    @Column(name = "simulated_speed_kph")     private BigDecimal simulatedSpeedKph;
    @Column(name = "simulated_vehicle_count") private Integer simulatedVehicleCount;
    @Column(name = "simulated_risk_score")    private BigDecimal simulatedRiskScore;
    @Column(name = "simulated_congestion")    private String simulatedCongestion;

    @Column(name = "delay_change_min")        private BigDecimal delayChangeMin;
    @Column(name = "parking_change_pct")      private BigDecimal parkingChangePct;
    @Column(name = "crowd_change_pct")        private BigDecimal crowdChangePct;

    /**
     * Whether the scenario named this zone or the engine inferred the effect.
     * A stated closure and an inferred spillover deserve different confidence.
     */
    @Column(name = "impact_source", nullable = false)
    private String impactSource = "DIRECT";
}
