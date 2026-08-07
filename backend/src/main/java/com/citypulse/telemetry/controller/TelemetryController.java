package com.citypulse.telemetry.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.security.CurrentUser;
import com.citypulse.telemetry.dto.TelemetryResponses;
import com.citypulse.telemetry.service.LiveMetricsService;
import com.citypulse.telemetry.service.LiveStreamService;
import com.citypulse.telemetry.service.StreamTicketService;
import com.citypulse.user.domain.Permissions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Live city conditions (PRD §9).
 *
 * <p>A city is addressable by slug as well as by identifier because the client
 * keeps the selected city in the URL, and {@code /live/bengaluru} is a link a
 * person can read and share. The UUID form stays for programmatic callers, who
 * should not have to know that slugs exist.
 */
@RestController
@RequestMapping("/api/v1/live")
@Validated
@Tag(name = "Live Intelligence", description = "Real-time city conditions from curated telemetry")
public class TelemetryController {

    private final LiveMetricsService metricsService;
    private final LiveStreamService streamService;
    private final StreamTicketService ticketService;
    private final CurrentUser currentUser;

    public TelemetryController(LiveMetricsService metricsService,
                               LiveStreamService streamService,
                               StreamTicketService ticketService,
                               CurrentUser currentUser) {
        this.metricsService = metricsService;
        this.streamService = streamService;
        this.ticketService = ticketService;
        this.currentUser = currentUser;
    }

    @GetMapping("/cities/{cityId}")
    @Operation(summary = "Current conditions for a city",
            description = "Requires telemetry:read. Returns KPIs and every monitored zone in one "
                          + "payload so the map and the tiles above it cannot disagree.")
    public ResponseEntity<ApiResponse<TelemetryResponses.CitySnapshot>> byCityId(
            @PathVariable UUID cityId) {
        return ResponseEntity.ok(ApiResponse.ok(metricsService.snapshotByCityId(cityId)));
    }

    @GetMapping("/by-slug/{slug}")
    @Operation(summary = "Current conditions for a city by slug",
            description = "Requires telemetry:read.")
    public ResponseEntity<ApiResponse<TelemetryResponses.CitySnapshot>> bySlug(
            @PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(metricsService.snapshotBySlug(slug)));
    }

    @GetMapping("/zones/{zoneId}/history")
    @Operation(summary = "A zone's recent curated history",
            description = "Requires telemetry:read. Defaults to the last six hours.")
    public ResponseEntity<ApiResponse<TelemetryResponses.ZoneHistory>> history(
            @PathVariable UUID zoneId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(ApiResponse.ok(metricsService.history(zoneId, from, to)));
    }

    /**
     * A city's recent history as one aggregated series.
     *
     * <p>Separate from the per-zone endpoint above because the aggregation rule —
     * average only across zones that reported — has to live in one place. Left
     * to callers, every chart in the product would fold twenty zone series in
     * the browser and each would drift from the snapshot's arithmetic in its own
     * way.
     */
    @GetMapping("/by-slug/{slug}/history")
    @Operation(summary = "A city's recent curated history, aggregated across its zones",
            description = "Requires telemetry:read. Defaults to the last six hours.")
    public ResponseEntity<ApiResponse<TelemetryResponses.CityHistory>> cityHistory(
            @PathVariable String slug,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(ApiResponse.ok(metricsService.cityHistory(slug, from, to)));
    }

    /**
     * Issues a one-minute, single-use ticket for opening a stream.
     *
     * <p>Needed because {@code EventSource} cannot send an {@code Authorization}
     * header, so the stream URL has to carry its own credential — and an access
     * token in a query string ends up in access logs, browser history and
     * {@code Referer} headers. See {@link StreamTicketService}.
     */
    @PostMapping("/by-slug/{slug}/stream-ticket")
    @Operation(summary = "Get a ticket for the live stream",
            description = "Requires telemetry:read. The ticket is valid once, for one minute, "
                          + "and only for this city.")
    @PreAuthorize("hasAuthority('" + Permissions.TELEMETRY_READ + "')")
    public ResponseEntity<ApiResponse<Map<String, Object>>> streamTicket(@PathVariable String slug) {
        // Resolve the city first: issuing a ticket for a city that does not exist
        // would defer the 404 to the stream, where an EventSource surfaces it as
        // an opaque connection error.
        metricsService.snapshotBySlug(slug);

        String ticket = ticketService.issue(currentUser.require().userUid(), slug);
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "ticket", ticket,
                "expiresInSeconds", StreamTicketService.TTL.toSeconds())));
    }

    /**
     * The live stream.
     *
     * <p>Returns a bare {@link SseEmitter} rather than the usual
     * {@code ApiResponse} envelope: this is a long-lived event stream, not a
     * request/response, and wrapping it would break the {@code EventSource}
     * contract every browser already implements.
     *
     * <p>Authenticated by ticket rather than by the JWT filter, so this path is
     * excluded from the bearer-token chain in {@code SecurityConfig}. The ticket
     * is redeemed — and thereby consumed — before any data is written.
     */
    @GetMapping(path = "/by-slug/{slug}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Subscribe to live conditions",
            description = "Requires a ticket from `/stream-ticket`. Emits a `snapshot` event on "
                          + "connect and then on every push cycle, plus periodic `heartbeat` "
                          + "events so an idle connection is not mistaken for a dead one. "
                          + "Browsers reconnect automatically using the retry interval the "
                          + "stream advertises.")
    public SseEmitter stream(@PathVariable String slug,
                             @RequestParam(required = false) String ticket) {
        // Declared optional so a missing ticket reaches the ticket service and
        // comes back as 403. Left required, Spring rejects it as a malformed
        // request with 400 before any of this runs — which tells a caller their
        // syntax was wrong when the truth is they were not authorised.
        ticketService.redeem(ticket, slug);
        return streamService.subscribe(slug);
    }
}
