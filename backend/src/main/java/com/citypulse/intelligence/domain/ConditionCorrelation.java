package com.citypulse.intelligence.domain;

import jakarta.persistence.*;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * How often two conditions occur together (PRD §12).
 *
 * <p>A measurement, not a causal claim. Lift above 1 means the pairing is more
 * common than chance; the counts are stored alongside so a reader can judge
 * whether the figure rests on anything.
 */
@Entity
@Table(name = "condition_correlations")
@Getter
public class ConditionCorrelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "city_id", nullable = false)   private Long cityId;
    @Column(name = "condition_a", nullable = false) private String conditionA;
    @Column(name = "condition_b", nullable = false) private String conditionB;

    /** P(B|A) / P(B). */
    @Column(name = "lift", nullable = false)       private BigDecimal lift;
    @Column(name = "support", nullable = false)    private BigDecimal support;
    @Column(name = "confidence", nullable = false) private BigDecimal confidence;

    @Column(name = "windows_with_a", nullable = false)    private Integer windowsWithA;
    @Column(name = "windows_with_both", nullable = false) private Integer windowsWithBoth;
    @Column(name = "windows_total", nullable = false)     private Integer windowsTotal;

    protected ConditionCorrelation() {
        // JPA.
    }
}
