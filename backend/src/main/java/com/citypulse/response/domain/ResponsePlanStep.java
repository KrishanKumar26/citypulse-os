package com.citypulse.response.domain;

import com.citypulse.common.domain.BaseEntity;
import com.citypulse.intervention.domain.Intervention;
import com.citypulse.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One line of a response plan.
 *
 * <p>Steps carry their own status because a plan is rarely all-or-nothing:
 * three of five done with the fourth blocked is the ordinary state of an
 * operational response, and a single plan-level status cannot express it.
 */
@Entity
@Table(name = "response_plan_steps")
@Getter
@Setter
@NoArgsConstructor
public class ResponsePlanStep extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false)
    private ResponsePlan plan;

    /**
     * Explicit, not insertion order.
     *
     * <p>Steps get reordered, and a response whose sequence depends on when rows
     * happened to be typed is not a plan.
     */
    @Column(name = "position", nullable = false)
    private int position;

    @Column(name = "instruction", nullable = false, length = 500)
    private String instruction;

    /**
     * True only for the one step the platform may supply.
     *
     * <p>The recommendedAction an alert rule attached when it fired. Recorded so
     * the interface can show which line came from a rule and which a person
     * wrote — presenting both in the same voice is how generated text starts
     * being trusted as considered.
     */
    @Column(name = "from_alert_rule", nullable = false)
    private boolean fromAlertRule = false;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "PENDING";

    /** Why a step is blocked or skipped. Without it a stalled plan says only that it stalled. */
    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "completed_at")
    private Instant completedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "completed_by")
    private User completedBy;

    /**
     * The action this step produced, when it produced a measurable one.
     *
     * <p>Nullable on purpose: "notified the response team" changes no telemetry,
     * and forcing an intervention row for it would fill the impact list with
     * entries that can never be measured.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "intervention_id")
    private Intervention intervention;
}
