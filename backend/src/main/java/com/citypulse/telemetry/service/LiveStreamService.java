package com.citypulse.telemetry.service;

import com.citypulse.telemetry.config.TelemetryProperties;
import com.citypulse.telemetry.dto.TelemetryResponses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-sent events for live city conditions (PRD §9).
 *
 * <p>SSE rather than WebSockets: the traffic is one-directional — the server
 * pushes conditions, the client never pushes back — and SSE gets automatic
 * browser-side reconnection with {@code Last-Event-ID} for free. A WebSocket
 * would mean writing that reconnection logic by hand for no capability gained.
 *
 * <p>Emitters are held per city so a push touches only the clients watching that
 * city. Broadcasting everything to everyone would scale with
 * (cities × viewers) instead of viewers.
 */
@Service
public class LiveStreamService {

    private static final Logger log = LoggerFactory.getLogger(LiveStreamService.class);

    /**
     * Named events, so a client can register separate handlers rather than
     * switch on a payload field.
     */
    public static final String EVENT_SNAPSHOT = "snapshot";
    public static final String EVENT_HEARTBEAT = "heartbeat";

    private final LiveMetricsService metricsService;
    private final TelemetryProperties properties;

    /** citySlug → open emitters. */
    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    /**
     * Monotonic event id, used as SSE {@code id:}.
     *
     * <p>The browser sends the last id back as {@code Last-Event-ID} on
     * reconnect. This stream is a full-snapshot stream rather than a delta one,
     * so the server does not need to replay anything — but the id still tells a
     * client whether it missed cycles, and makes a dropped connection visible
     * instead of silent.
     */
    private final AtomicLong eventId = new AtomicLong();

    public LiveStreamService(LiveMetricsService metricsService, TelemetryProperties properties) {
        this.metricsService = metricsService;
        this.properties = properties;
    }

    /**
     * Registers a client for a city and sends it the current state immediately.
     *
     * <p>The first frame is sent synchronously rather than waiting for the next
     * push cycle: otherwise a dashboard opening just after a tick would sit empty
     * for the whole interval, which reads as a broken page.
     */
    public SseEmitter subscribe(String citySlug) {
        // No server-side timeout. The interval and heartbeat below keep the
        // connection live; a timeout here would drop healthy clients on a fixed
        // schedule and make reconnects look like server faults.
        SseEmitter emitter = new SseEmitter(0L);

        subscribers.computeIfAbsent(citySlug, key -> ConcurrentHashMap.newKeySet()).add(emitter);

        emitter.onCompletion(() -> remove(citySlug, emitter));
        emitter.onTimeout(() -> remove(citySlug, emitter));
        emitter.onError(e -> remove(citySlug, emitter));

        try {
            TelemetryResponses.CitySnapshot snapshot = metricsService.snapshotForAuthorisedSubscriber(citySlug);
            emitter.send(SseEmitter.event()
                    .id(String.valueOf(eventId.incrementAndGet()))
                    .name(EVENT_SNAPSHOT)
                    .reconnectTime(properties.streamInterval().toMillis())
                    .data(snapshot));
        } catch (IOException e) {
            // The client vanished between connecting and the first write.
            remove(citySlug, emitter);
            emitter.completeWithError(e);
        } catch (RuntimeException e) {
            remove(citySlug, emitter);
            emitter.completeWithError(e);
            throw e;
        }

        log.debug("SSE subscriber added for {} ({} total)", citySlug, count(citySlug));
        return emitter;
    }

    /**
     * Pushes a fresh snapshot to every city that has listeners.
     *
     * <p>Only cities with subscribers are queried. A city nobody is watching
     * costs nothing, which is what keeps the cost proportional to viewers rather
     * than to the size of the platform.
     */
    @Scheduled(fixedDelayString = "${citypulse.telemetry.stream-interval:PT5S}")
    public void push() {
        for (Map.Entry<String, Set<SseEmitter>> entry : subscribers.entrySet()) {
            String citySlug = entry.getKey();
            Set<SseEmitter> emitters = entry.getValue();
            if (emitters.isEmpty()) {
                subscribers.remove(citySlug, emitters);
                continue;
            }

            TelemetryResponses.CitySnapshot snapshot;
            try {
                snapshot = metricsService.snapshotForAuthorisedSubscriber(citySlug);
            } catch (RuntimeException e) {
                // A failed read must not tear down healthy subscriptions — the
                // next cycle may well succeed, and dropping every client on a
                // transient database blip is worse than skipping one frame.
                log.warn("Live snapshot failed for {}: {}", citySlug, e.getMessage());
                continue;
            }

            String id = String.valueOf(eventId.incrementAndGet());
            emitters.removeIf(emitter -> !trySend(emitter, id, EVENT_SNAPSHOT, snapshot));
        }
    }

    /**
     * Comment-only frames on idle connections.
     *
     * <p>Separate from {@link #push()} because a stream can be pushing fine and
     * still need this: intermediaries close sockets that go quiet, and without a
     * heartbeat a client cannot distinguish a dead connection from a calm city.
     */
    @Scheduled(fixedDelayString = "${citypulse.telemetry.heartbeat-interval:PT20S}")
    public void heartbeat() {
        Instant now = Instant.now();
        for (Set<SseEmitter> emitters : subscribers.values()) {
            emitters.removeIf(emitter ->
                    !trySend(emitter, null, EVENT_HEARTBEAT, Map.of("at", now.toString())));
        }
    }

    /**
     * @return true when the write succeeded and the emitter should be kept
     */
    private boolean trySend(SseEmitter emitter, String id, String name, Object payload) {
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event().name(name).data(payload);
            if (id != null) {
                event = event.id(id);
            }
            emitter.send(event);
            return true;
        } catch (IOException | IllegalStateException e) {
            // A client that closed its tab is the normal case, not an error.
            // Complete the emitter so its resources are released either way.
            try {
                emitter.complete();
            } catch (RuntimeException ignored) {
                // Already completed.
            }
            return false;
        }
    }

    private void remove(String citySlug, SseEmitter emitter) {
        Set<SseEmitter> emitters = subscribers.get(citySlug);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }

    /** Open subscriptions for a city. Exposed for tests and diagnostics. */
    public int count(String citySlug) {
        return Objects.requireNonNullElse(subscribers.get(citySlug), Set.<SseEmitter>of()).size();
    }

    public int totalSubscribers() {
        return subscribers.values().stream().mapToInt(Set::size).sum();
    }
}
