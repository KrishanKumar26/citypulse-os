package com.citypulse.geo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Write payloads for geography. Coordinate bounds are asserted here as well as
 * by database check constraints — validation gives the caller a useful field
 * error, the constraint guarantees the invariant regardless of how a row is
 * written.
 */
public final class GeoRequests {

    private GeoRequests() {
    }

    public record CreateCity(
            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$",
                    message = "Slug must be lowercase words separated by hyphens")
            String slug,

            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String country,

            @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "Country code must be two uppercase letters")
            String countryCode,

            @NotBlank @Size(max = 64)
            @Pattern(regexp = "^[A-Za-z]+/[A-Za-z_+\\-]+$", message = "Timezone must be an IANA zone id")
            String timezone,

            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal centerLatitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal centerLongitude,
            @Min(1) @Max(20) Integer defaultZoom,
            @PositiveOrZero Integer population,
            @DecimalMin("0.0") BigDecimal areaSqKm,

            /* False only for a city fed by a real data source. Defaults to true so
               a city added without thought is labelled demo rather than passing
               synthetic data off as live. */
            Boolean demoData
    ) {
    }

    public record UpdateCity(
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Size(max = 80) String country,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}$") String countryCode,
            @NotBlank @Size(max = 64) String timezone,
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal centerLatitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal centerLongitude,
            @Min(1) @Max(20) Integer defaultZoom,
            @PositiveOrZero Integer population,
            @DecimalMin("0.0") BigDecimal areaSqKm,
            @NotNull Boolean active
    ) {
    }

    public record CreateZone(
            @NotBlank @Size(max = 48)
            @Pattern(regexp = "^[A-Z0-9]+(-[A-Z0-9]+)*$",
                    message = "Zone code must be uppercase alphanumerics separated by hyphens")
            String code,

            @NotBlank @Size(max = 120) String name,

            @NotBlank
            @Pattern(regexp = "RESIDENTIAL|COMMERCIAL|INDUSTRIAL|MIXED|TRANSIT_HUB|EDUCATIONAL|RECREATIONAL|AIRPORT",
                    message = "Unknown zone type")
            String zoneType,

            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal centerLatitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal centerLongitude,
            @DecimalMin("0.0") BigDecimal areaSqKm,
            @PositiveOrZero Integer population,

            /* Vehicles per hour before congestion. The denominator for congestion
               percentage, so it must be positive if supplied. */
            @Min(1) Integer roadCapacityVph,

            @Size(max = 200_000, message = "Boundary GeoJSON is too large")
            String boundaryGeoJson
    ) {
    }

    public record UpdateZone(
            @NotBlank @Size(max = 120) String name,

            @NotBlank
            @Pattern(regexp = "RESIDENTIAL|COMMERCIAL|INDUSTRIAL|MIXED|TRANSIT_HUB|EDUCATIONAL|RECREATIONAL|AIRPORT",
                    message = "Unknown zone type")
            String zoneType,

            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal centerLatitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal centerLongitude,
            @DecimalMin("0.0") BigDecimal areaSqKm,
            @PositiveOrZero Integer population,
            @Min(1) Integer roadCapacityVph,
            @Size(max = 200_000) String boundaryGeoJson,
            @NotNull Boolean active
    ) {
    }
}
