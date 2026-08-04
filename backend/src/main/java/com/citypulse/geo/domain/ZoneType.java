package com.citypulse.geo.domain;

/**
 * Land-use classification. Drives the baseline traffic and crowd profile the
 * synthetic generator and forecast features expect for a zone.
 */
public enum ZoneType {
    RESIDENTIAL,
    COMMERCIAL,
    INDUSTRIAL,
    MIXED,
    TRANSIT_HUB,
    EDUCATIONAL,
    RECREATIONAL,
    AIRPORT
}
