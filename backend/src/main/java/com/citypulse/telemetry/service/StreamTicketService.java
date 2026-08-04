package com.citypulse.telemetry.service;

import com.citypulse.common.exception.Exceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single-use tickets for opening an authenticated SSE stream.
 *
 * <p>This exists because of a browser limitation, not a preference. The
 * {@code EventSource} API cannot set request headers, so a stream URL cannot
 * carry {@code Authorization: Bearer …} the way every other endpoint does. The
 * obvious workaround — putting the access token in the query string — is worse
 * than it looks: query strings are written to web server access logs, kept in
 * browser history, and forwarded in {@code Referer} headers to any third-party
 * resource the page loads. A 15-minute access token sitting in all three places
 * is a real credential leak.
 *
 * <p>A ticket instead is opaque, valid for one minute, usable exactly once, and
 * bound to both the user who requested it and the city they asked for. Even if
 * one is captured from a log it is almost certainly already spent, and if it is
 * not, it grants read access to one city's public-facing conditions for a few
 * seconds — not the bearer's whole session.
 *
 * <p>Held in memory deliberately. Tickets are worthless after sixty seconds, so
 * persisting them would add a table, a cleanup job and cross-instance
 * consistency concerns to protect data that is about to be void anyway. The
 * trade-off is that a ticket is only valid on the instance that issued it, which
 * matters behind a load balancer without sticky sessions — recorded in
 * docs/SECURITY.md as the thing to revisit when the backend is scaled out.
 */
@Service
public class StreamTicketService {

    private static final Logger log = LoggerFactory.getLogger(StreamTicketService.class);

    /**
     * Long enough for a client to receive the ticket and open the stream on a
     * slow connection; short enough that a leaked one is stale before it can be
     * found in a log.
     */
    public static final Duration TTL = Duration.ofMinutes(1);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> issued = new ConcurrentHashMap<>();

    private record Ticket(UUID userUid, String citySlug, Instant expiresAt) {
        boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }

    /**
     * Issues a ticket for one user and one city.
     *
     * @return the opaque ticket value; the caller passes it as a query parameter
     */
    public String issue(UUID userUid, String citySlug) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        issued.put(ticket, new Ticket(userUid, citySlug, Instant.now().plus(TTL)));
        return ticket;
    }

    /**
     * Redeems a ticket, consuming it.
     *
     * <p>Removed before validation rather than after, so a replayed value cannot
     * succeed twice even if two requests arrive simultaneously.
     *
     * @return the user the ticket was issued to
     * @throws Exceptions.Forbidden when the ticket is unknown, expired, already
     *                              used, or was issued for a different city
     */
    public UUID redeem(String ticket, String citySlug) {
        if (ticket == null || ticket.isBlank()) {
            throw new Exceptions.Forbidden("A stream ticket is required");
        }

        Ticket found = issued.remove(ticket);
        if (found == null) {
            throw new Exceptions.Forbidden("Stream ticket is invalid or has already been used");
        }
        if (found.isExpired(Instant.now())) {
            throw new Exceptions.Forbidden("Stream ticket has expired");
        }
        // A ticket for one city must not open another's stream, or the binding
        // would be decorative.
        if (!found.citySlug().equals(citySlug)) {
            throw new Exceptions.Forbidden("Stream ticket was not issued for this city");
        }
        return found.userUid();
    }

    /**
     * Drops expired tickets.
     *
     * <p>Redemption removes a ticket, but one that is issued and never used would
     * otherwise sit in the map forever — an unbounded leak fed directly by
     * client behaviour.
     */
    @Scheduled(fixedDelay = 60_000)
    void evictExpired() {
        Instant now = Instant.now();
        int before = issued.size();
        issued.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
        int removed = before - issued.size();
        if (removed > 0) {
            log.debug("Evicted {} expired stream tickets, {} outstanding", removed, issued.size());
        }
    }

    /** Outstanding unredeemed tickets. Exposed for tests. */
    public int outstanding() {
        return issued.size();
    }
}
