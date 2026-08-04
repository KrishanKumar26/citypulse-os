package com.citypulse.support;

import com.citypulse.telemetry.domain.ZoneMetric;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Builds {@link ZoneMetric} instances for tests.
 *
 * <p>Reflection rather than setters, deliberately. {@code ZoneMetric} is a
 * curated row the data pipeline owns: the application only ever reads it, and
 * the absence of setters is what enforces that. Adding them so tests could
 * construct one would weaken the production type to serve the test suite, and
 * the next person would reasonably assume writing to it was supported.
 *
 * <p>The cost is that a renamed field breaks this fixture at runtime rather than
 * at compile time — accepted because {@link #set} fails loudly with the field
 * name, and the alternative is worse.
 */
public final class ZoneMetricFixture {

    private final ZoneMetric metric;

    private ZoneMetricFixture() {
        try {
            var constructor = ZoneMetric.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            this.metric = constructor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("ZoneMetric no longer has a no-arg constructor", e);
        }
        // Sensible defaults: a quiet, well-sampled zone that fires no rule. Each
        // test then changes only the field it is about, so what is under test is
        // obvious from the builder call rather than buried in a wall of values.
        set("zoneId", 1L);
        set("windowStart", Instant.parse("2026-08-04T10:00:00Z"));
        set("windowEnd", Instant.parse("2026-08-04T10:05:00Z"));
        set("sampleCount", 6);
        set("activeIncidents", (short) 0);
        set("activeEvents", (short) 0);
        set("demoData", true);
        set("computedAt", Instant.parse("2026-08-04T10:05:10Z"));
    }

    public static ZoneMetricFixture aMetric() {
        return new ZoneMetricFixture();
    }

    public ZoneMetricFixture occupancy(String ratio) {
        return set("occupancyRatio", new BigDecimal(ratio));
    }

    public ZoneMetricFixture speed(String kph) {
        return set("averageSpeedKph", new BigDecimal(kph));
    }

    public ZoneMetricFixture congestion(String level) {
        return set("congestionLevel", level);
    }

    public ZoneMetricFixture aqi(Integer value) {
        return set("aqi", value);
    }

    public ZoneMetricFixture aqiCategory(String category) {
        return set("aqiCategory", category);
    }

    public ZoneMetricFixture incidents(int count) {
        return set("activeIncidents", (short) count);
    }

    public ZoneMetricFixture risk(String score, String level) {
        set("riskScore", new BigDecimal(score));
        return set("riskLevel", level);
    }

    public ZoneMetricFixture samples(Integer count) {
        return set("sampleCount", count);
    }

    public ZoneMetricFixture windowStart(Instant when) {
        return set("windowStart", when);
    }

    public ZoneMetric build() {
        return metric;
    }

    private ZoneMetricFixture set(String fieldName, Object value) {
        try {
            Field field = ZoneMetric.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(metric, value);
            return this;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "ZoneMetric has no field '" + fieldName + "' — the fixture needs updating", e);
        }
    }
}
