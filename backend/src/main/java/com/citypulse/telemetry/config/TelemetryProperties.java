package com.citypulse.telemetry.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tuning for the live intelligence layer.
 *
 * @param freshnessBudget how old the newest curated window may be before a
 *                        snapshot is marked stale. The pipeline writes
 *                        five-minute windows, so anything beyond a few of them
 *                        means ingestion has stopped rather than that the city
 *                        is quiet — a distinction the UI must be able to make
 *                        instead of charting a flat line as if it were real.
 * @param maxAge          how far back a window may be and still count as "current"
 *                        at all. Beyond this the zone reports no data rather than
 *                        yesterday's conditions dressed up as today's.
 * @param streamInterval  how often the SSE stream re-reads and pushes. Matched to
 *                        the window size: polling faster would resend identical
 *                        numbers, and slower would leave the dashboard behind the
 *                        data it already has.
 * @param heartbeatInterval how often a comment frame is sent on an idle stream.
 *                        Proxies and load balancers close connections that go
 *                        quiet, and the client cannot tell that from a server
 *                        that has nothing to say.
 */
@ConfigurationProperties(prefix = "citypulse.telemetry")
public record TelemetryProperties(
        @NotNull Duration freshnessBudget,
        @NotNull Duration maxAge,
        @NotNull Duration streamInterval,
        @NotNull Duration heartbeatInterval,
        @Positive int maxHistoryWindows
) {
}
