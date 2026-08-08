package com.citypulse.intervention.domain;

import com.citypulse.common.domain.BaseEntity;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
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
 * Something a person states they did about a situation (PRD §16).
 *
 * <p>Never generated. The platform cannot observe a corridor being rerouted or
 * a signal timing being changed; someone records that it happened, and the row
 * carries who. An intervention with no author would be an assertion nobody
 * owns, and the whole record rests on someone having been there.
 *
 * <p>No outcome is stored on this entity. What followed is derived on read from
 * the curated windows, so a corrected window or a relearned baseline changes the
 * answer rather than leaving a stale verdict in a column.
 */
@Entity
@Table(name = "interventions")
@Getter
@Setter
@NoArgsConstructor
public class Intervention extends BaseEntity {

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", length = 1000)
    private String description;

    /**
     * Free text, not an enum.
     *
     * <p>The set of things a city can do is not known in advance. A wrong enum
     * forces real actions to be filed under "OTHER", which destroys the only
     * field that says what actually happened.
     */
    @Column(name = "action_type", nullable = false, length = 64)
    private String actionType;

    /** Null for a city-wide action, which has no zone baseline to compare against. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "zone_id")
    private Zone zone;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "city_id", nullable = false)
    private City city;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    /** Null while still in effect; the measurement then runs to now, provisionally. */
    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recorded_by", nullable = false)
    private User recordedBy;

    /**
     * How much history either side the comparison spans.
     *
     * <p>Stored per intervention so a reader can tell a judgement made over ten
     * minutes from one made over two hours — they are not the same claim.
     */
    @Column(name = "comparison_minutes", nullable = false)
    private int comparisonMinutes = 60;

    @Column(name = "notes", length = 1000)
    private String notes;

    @Column(name = "demo_data", nullable = false)
    private boolean demoData = true;
}
