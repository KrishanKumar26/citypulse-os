package com.citypulse.response.domain;

import com.citypulse.alert.domain.Alert;
import com.citypulse.common.domain.BaseEntity;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.user.domain.User;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * What we intend to do about a situation (PRD §16).
 *
 * <p>The part that was missing between an alert and an intervention. An alert
 * states a condition; an intervention records an action already taken. Neither
 * holds the middle: what we mean to do, in what order, and who is doing it.
 *
 * <p>Authored, never generated. The platform may supply exactly one step — the
 * recommendedAction an alert rule attached when it fired, which is a string a
 * real rule computed. Everything else is typed by a person, and the step records
 * which it was so the two are never shown in the same voice. A plausible list of
 * actions assembled by the system would be indistinguishable from a considered
 * one, and this is the screen where that gets acted on rather than merely read.
 */
@Entity
@Table(name = "response_plans")
@Getter
@Setter
@NoArgsConstructor
public class ResponsePlan extends BaseEntity {

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "summary", length = 1000)
    private String summary;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    /** Null for a city-wide response. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    /**
     * What prompted this, when something did.
     *
     * <p>Nullable because a plan can come from judgement rather than from a
     * raised alert, and requiring one would make an operator invent a trigger.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "alert_id")
    private Alert alert;

    @Column(name = "priority", nullable = false, length = 16)
    private String priority = "MEDIUM";

    @Column(name = "status", nullable = false, length = 16)
    private String status = "DRAFT";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    /** Distinct from the author: writing a plan and owning it are different acts. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "demo_data", nullable = false)
    private boolean demoData = true;

    @OneToMany(mappedBy = "plan", cascade = CascadeType.ALL, orphanRemoval = true,
            fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ResponsePlanStep> steps = new ArrayList<>();
}
