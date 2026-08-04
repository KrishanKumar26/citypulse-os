package com.citypulse.common.time;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Clock reads for values that will be stored.
 *
 * <p>PostgreSQL's {@code TIMESTAMPTZ} holds microseconds. {@code Instant.now()}
 * on Linux returns nanoseconds. Writing one to the other silently truncates, so
 * the value a service holds in memory stops matching the value in the database
 * — and an API that returns the first, then later returns the second, reports
 * two different timestamps for the same event.
 *
 * <p>That is not theoretical. It failed a test in CI and passed locally, because
 * macOS's clock already returns microsecond resolution and the truncation was
 * invisible there. A client caching a returned timestamp and comparing it to a
 * later read would have seen a mismatch it could not explain.
 *
 * <p>Use {@link #now()} for any instant that will be persisted. Plain
 * {@code Instant.now()} remains correct for comparisons, deadlines and
 * durations, where the extra precision costs nothing.
 */
public final class Timestamps {

    private Timestamps() {
    }

    /**
     * The current instant at the precision the database can actually store.
     *
     * <p>Truncated rather than rounded: a stored timestamp must never be in the
     * future relative to the moment it was taken.
     */
    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** Truncates an existing instant to storable precision. */
    public static Instant storable(Instant instant) {
        return instant == null ? null : instant.truncatedTo(ChronoUnit.MICROS);
    }
}
