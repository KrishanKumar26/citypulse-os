package com.citypulse.intelligence.service;

import com.citypulse.intelligence.domain.ConditionCorrelation;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Turns measurements into sentences (PRD §15).
 *
 * <p>Rule-based, and deliberately so. The PRD calls for a `RuleBasedProvider`
 * behind an interface that a language model could later sit behind — but the
 * ordering matters: a generated sentence can be fluent and wrong, and every
 * statement this platform makes has to be checkable against the row that
 * produced it. Templates over measured numbers cannot drift from their evidence.
 *
 * <p>Each method takes the figures and returns prose containing them. Nothing is
 * softened, rounded away or asserted beyond what was passed in.
 */
public final class Explanations {

    private Explanations() {
    }

    /** Readable names for the coarse conditions the correlation engine measures. */
    private static final Map<String, String> CONDITIONS = Map.ofEntries(
            Map.entry("RAIN", "rain"),
            Map.entry("HEAVY_RAIN", "heavy rain"),
            Map.entry("INCIDENT_OPEN", "an open incident"),
            Map.entry("EVENT_ACTIVE", "a scheduled event"),
            Map.entry("HIGH_CONGESTION", "high congestion"),
            Map.entry("CRITICAL_CONGESTION", "critical congestion"),
            Map.entry("POOR_AIR", "poor air quality"),
            Map.entry("HIGH_RISK", "high composite risk"),
            Map.entry("SLOW_TRAFFIC", "traffic below 15 km/h"));

    public static String readable(String condition) {
        return CONDITIONS.getOrDefault(condition, condition.replace('_', ' ').toLowerCase());
    }

    /**
     * States a correlation without implying cause.
     *
     * <p>"Coincides with" rather than "causes" or even "leads to": the platform
     * measured co-occurrence and nothing more. Rain and congestion rise together
     * partly because both are heavier at the same times of day, and the engine
     * has no way to separate that from rain making traffic worse.
     */
    public static String forCorrelation(ConditionCorrelation c) {
        double confidencePct = c.getConfidence().doubleValue() * 100;
        return "%s coincides with %s in %.0f%% of windows — %.1fx more often than %s occurs generally. Measured across %,d windows, %,d of them with both."
                .formatted(
                        capitalise(readable(c.getConditionA())),
                        readable(c.getConditionB()),
                        confidencePct,
                        c.getLift().doubleValue(),
                        readable(c.getConditionB()),
                        c.getWindowsTotal(),
                        c.getWindowsWithBoth());
    }

    /**
     * Summarises what historically followed a situation.
     *
     * <p>Returns the "insufficient" form when there is too little to go on. The
     * two cases are separate methods rather than one with a null return, so a
     * caller cannot accidentally render an empty summary as a confident one.
     */
    public static String forMemory(int matchCount,
                                   BigDecimal medianOccupancyChange,
                                   BigDecimal medianSpeedChange,
                                   String rainBand,
                                   String hourBand,
                                   boolean hadEvent,
                                   boolean relaxed) {
        String situation = describeSituation(rainBand, hourBand, hadEvent);
        String widened = relaxed
                ? " (matched on a widened fingerprint, because the exact combination was too rare)"
                : "";

        StringBuilder outcome = new StringBuilder();
        if (medianOccupancyChange != null) {
            outcome.append("congestion typically %s %.0f%%".formatted(
                    medianOccupancyChange.signum() >= 0 ? "rose" : "fell",
                    Math.abs(medianOccupancyChange.doubleValue())));
        }
        if (medianSpeedChange != null) {
            if (outcome.length() > 0) {
                outcome.append(" and ");
            }
            outcome.append("speed %s %.0f%%".formatted(
                    medianSpeedChange.signum() >= 0 ? "rose" : "fell",
                    Math.abs(medianSpeedChange.doubleValue())));
        }

        return "%s has occurred %,d times before%s. Over the following two hours, %s."
                .formatted(situation, matchCount, widened,
                        outcome.length() > 0 ? outcome : "no consistent change was measured");
    }

    /** The form used when the memory cannot support a claim. */
    public static String insufficientMemory(int matchCount, int required, String rainBand,
                                            String hourBand, boolean hadEvent) {
        return "%s has been observed only %d time%s, which is fewer than the %d needed before a historical pattern means anything. No outcome is reported rather than a figure drawn from too little."
                .formatted(describeSituation(rainBand, hourBand, hadEvent),
                        matchCount, matchCount == 1 ? "" : "s", required);
    }

    private static String describeSituation(String rainBand, String hourBand, boolean hadEvent) {
        String rain = switch (rainBand) {
            case "NONE" -> "Dry conditions";
            case "LIGHT" -> "Light rain";
            case "MODERATE" -> "Moderate rain";
            case "HEAVY" -> "Heavy rain";
            default -> rainBand;
        };
        String hour = switch (hourBand) {
            case "OVERNIGHT" -> "overnight";
            case "MORNING_PEAK" -> "during the morning peak";
            case "MIDDAY" -> "at midday";
            case "EVENING_PEAK" -> "during the evening peak";
            case "EVENING" -> "in the evening";
            default -> hourBand;
        };
        return "%s %s%s".formatted(rain, hour, hadEvent ? " with an event under way" : "");
    }

    private static String capitalise(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }
}
