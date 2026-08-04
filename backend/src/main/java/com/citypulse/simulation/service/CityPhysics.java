package com.citypulse.simulation.service;

/**
 * The relationships the platform models a city with.
 *
 * <p>These are ports of {@code data-engineering/common/transforms.py}, and the
 * duplication is deliberate but not free. The pipeline computes observed
 * conditions in Python; the simulator computes counterfactual conditions in Java
 * in the request path, because a scenario has to feel immediate and a round trip
 * to a Python job would not. The two implementations must agree exactly, or a
 * simulated risk score of 70 would mean something different from an observed 70
 * and the before/after comparison the whole feature rests on would be
 * meaningless.
 *
 * <p>{@code CityPhysicsParityTest} pins every constant here against the Python
 * source, so a change on either side fails a build rather than silently drifting.
 *
 * <p>Nothing in this class is invented for the simulator. Every curve and weight
 * already governs how the platform reads real data; the simulator applies them
 * to hypothetical inputs rather than measured ones.
 */
public final class CityPhysics {

    private CityPhysics() {
    }

    // --- Speed from loading (transforms.speed_from_occupancy) ----------------

    /**
     * BPR coefficients for signalised urban arterials.
     *
     * <p>Not the textbook highway values (α=0.15, β=4): those degrade travel
     * time only ~15% at capacity, which would have a zone at 83% occupancy
     * reporting near-free-flow speed while labelled HIGH. That mismatch was a
     * real defect found in Phase 3.
     */
    public static final double BPR_ALPHA = 0.85;
    public static final double BPR_BETA = 5.0;

    public static final double FREE_FLOW_KPH = 48.0;
    public static final double JAM_KPH = 8.0;

    /**
     * Speed implied by how full the road is.
     *
     * <p>Journey time rises as {@code 1 + α(v/c)^β}, so speed holds up while
     * there is slack and then falls away sharply near and above capacity. A
     * linear model would understate the last 20% of loading — exactly the range
     * the platform exists to warn about.
     */
    public static double speedFromOccupancy(double occupancyRatio, double freeFlowKph, double jamKph) {
        double loading = Math.max(0.0, occupancyRatio);
        double travelTimeFactor = 1.0 + BPR_ALPHA * Math.pow(loading, BPR_BETA);
        double speed = freeFlowKph / travelTimeFactor;
        // Floored at jam speed — gridlocked traffic still creeps — and capped at
        // free flow, since an empty road does not exceed it.
        return Math.min(freeFlowKph, Math.max(jamKph, speed));
    }

    public static double speedFromOccupancy(double occupancyRatio) {
        return speedFromOccupancy(occupancyRatio, FREE_FLOW_KPH, JAM_KPH);
    }

    // --- Congestion bands (transforms.congestion_level) ----------------------

    public static final double BAND_NORMAL = 0.55;
    public static final double BAND_MODERATE = 0.80;
    public static final double BAND_HIGH = 1.00;

    public static String congestionLevel(double occupancyRatio) {
        if (occupancyRatio <= BAND_NORMAL) return "NORMAL";
        if (occupancyRatio <= BAND_MODERATE) return "MODERATE";
        if (occupancyRatio <= BAND_HIGH) return "HIGH";
        return "CRITICAL";
    }

    // --- Composite risk (transforms.risk_score) ------------------------------

    public static final double W_CONGESTION = 0.40;
    public static final double W_AIR_QUALITY = 0.20;
    public static final double W_INCIDENTS = 0.25;
    public static final double W_WEATHER = 0.15;

    /** 1.25x capacity is treated as fully saturated risk. */
    public static final double CONGESTION_SATURATION = 1.25;
    /** 400 is the top of VERY_POOR; SEVERE saturates the contribution. */
    public static final double AQI_SATURATION = 400.0;
    public static final double INCIDENT_SATURATION = 4.0;
    public static final double RAIN_SATURATION_MM_H = 20.0;

    /**
     * Composite 0–100 risk for one zone.
     *
     * <p>Returns null when nothing contributes. A zone with no data is not low
     * risk — it is unknown, and reporting 0 would be a measurement never made.
     *
     * <p>Missing components are excluded and the remaining weights renormalised,
     * so a zone with traffic but no air-quality reading still scores on what it
     * has rather than being penalised for the gap.
     */
    public static Double riskScore(Double occupancyRatio, Integer aqi,
                                   int activeIncidents, Double precipitationMmH) {
        double totalWeight = 0.0;
        double weighted = 0.0;

        if (occupancyRatio != null) {
            weighted += W_CONGESTION * clamp01(occupancyRatio / CONGESTION_SATURATION);
            totalWeight += W_CONGESTION;
        }
        if (aqi != null) {
            weighted += W_AIR_QUALITY * clamp01(aqi / AQI_SATURATION);
            totalWeight += W_AIR_QUALITY;
        }
        // Incidents always contribute: zero incidents is a real measurement of
        // low risk, unlike a missing feed.
        weighted += W_INCIDENTS * clamp01(activeIncidents / INCIDENT_SATURATION);
        totalWeight += W_INCIDENTS;

        if (precipitationMmH != null) {
            weighted += W_WEATHER * clamp01(precipitationMmH / RAIN_SATURATION_MM_H);
            totalWeight += W_WEATHER;
        }

        if (totalWeight == 0.0) {
            return null;
        }
        return Math.round(100.0 * weighted / totalWeight * 100.0) / 100.0;
    }

    public static String riskLevel(Double score) {
        if (score == null) return null;
        if (score <= 25.0) return "NORMAL";
        if (score <= 50.0) return "MODERATE";
        if (score <= 75.0) return "HIGH";
        return "CRITICAL";
    }

    // --- Rain's effect on capacity -------------------------------------------

    /**
     * How much usable road capacity survives given rainfall.
     *
     * <p>Wet roads lengthen following distances and slow turning movements, so
     * the same tarmac carries fewer vehicles per hour. Mirrors the generator's
     * weather coupling: a linear reduction that bottoms out at 65% — heavy rain
     * degrades a road badly but does not close it.
     */
    public static final double MIN_RAIN_CAPACITY_FACTOR = 0.65;

    public static double rainCapacityFactor(double precipitationMmH) {
        if (precipitationMmH <= 0.0) {
            return 1.0;
        }
        double reduction = Math.min(1.0, precipitationMmH / RAIN_SATURATION_MM_H) * 0.35;
        return Math.max(MIN_RAIN_CAPACITY_FACTOR, 1.0 - reduction);
    }

    private static double clamp01(double value) {
        return Math.min(1.0, Math.max(0.0, value));
    }
}
