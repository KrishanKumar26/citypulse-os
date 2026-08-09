package com.citypulse.telemetry.domain;

/**
 * A credit a feed's licence requires be displayed.
 *
 * <p>Stored per source rather than hardcoded in the client, because for the
 * station feed the required credit is not knowable in advance: WAQI's terms
 * oblige attribution to "the World Air Quality Index Project as well as
 * originating EPA", and the originating agency differs from station to station.
 * The ingester writes the union of the credits for the stations whose readings
 * this deployment actually holds, so the list describes the data on screen
 * rather than the data the provider could have supplied.
 *
 * <p>{@code url} may be blank — some agencies are named in a response without
 * one — and the client renders those as plain text rather than a dead link.
 *
 * @param name who must be credited
 * @param url  where they publish, if the provider gave one
 */
public record Attribution(String name, String url) {
}
