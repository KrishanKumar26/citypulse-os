package com.citypulse.telemetry.domain;

import com.citypulse.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A feed the platform ingests from (PRD §19, §43).
 *
 * <p>Seeded by migration V5 rather than created at runtime, so the foreign keys
 * on the event tables have a target before the generator produces anything, and
 * so a feed can be paused from the database without redeploying it.
 *
 * <p>{@code SYNTHETIC} is a first-class ingestion mode, not a fallback: the PRD
 * requires the platform to run with no external API at all. A source that says
 * SYNTHETIC is telling the truth about where its rows come from, which is the
 * point of surfacing this table at all.
 *
 * <p>{@code config} deliberately has no getter here. It holds non-secret shaping
 * parameters, but "non-secret by convention" is not a guarantee, and nothing in
 * the product needs to render it. Leaving it unmapped means a future value that
 * should not have been put there cannot leak through an API response.
 */
@Entity
@Table(name = "data_sources")
@Getter
@Setter
@NoArgsConstructor
public class DataSource extends BaseEntity {

    @Column(name = "code", nullable = false, length = 64)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    /** TRAFFIC, WEATHER, AIR_QUALITY, INCIDENT or CITY_EVENT. */
    @Column(name = "source_type", nullable = false, length = 24)
    private String sourceType;

    /** SYNTHETIC, REST_API, STREAM or FILE. */
    @Column(name = "ingestion_mode", nullable = false, length = 24)
    private String ingestionMode;

    @Column(name = "status", nullable = false, length = 24)
    private String status;

    /**
     * When this source last delivered anything.
     *
     * <p>Nullable, and null means never — not "a long time ago". A source that
     * has never produced a row and one that stopped an hour ago are different
     * problems, and rendering both as a dash would hide which.
     */
    @Column(name = "last_ingested_at")
    private Instant lastIngestedAt;

    @Column(name = "demo_data", nullable = false)
    private boolean demoData = true;

    /**
     * What kind of thing produced this feed's numbers: {@code MEASURED} (an
     * instrument), {@code MODELLED} (a physical model of the real atmosphere)
     * or {@code SYNTHETIC} (this platform's generator).
     *
     * <p>Held on the source rather than on each reading because a source's kind
     * does not vary row by row — a monitoring station cannot emit a modelled
     * value and CAMS cannot emit a measured one. Per-row it could disagree with
     * itself, and there would be no rule for which copy to believe.
     *
     * <p>Distinct from {@link #demoData}, which asks only whether this platform
     * invented the row. Both real feeds answer that the same way and differ
     * here, which is exactly the distinction a reader needs.
     */
    @Column(name = "provenance", nullable = false, length = 16)
    private String provenance = "SYNTHETIC";

    /**
     * Credits this feed's licence requires be shown wherever its data is.
     *
     * <p>Written by the ingester from the provider's own response, so it names
     * the agencies behind the readings actually held. Empty for the generated
     * feeds, which are this platform's own output and owe no credit.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attribution", nullable = false)
    private List<Attribution> attribution = new ArrayList<>();
}
