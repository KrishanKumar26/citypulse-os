package com.citypulse.simulation.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Scenario inputs (PRD §14).
 *
 * <p>Every field is optional and every bound is enforced. The bounds are not
 * arbitrary tidiness: the engine's curves are only meaningful over the range the
 * platform has observed, and a request for 500 mm/h of rain would produce a
 * confident number about conditions no model here has any basis for. Refusing is
 * more honest than extrapolating.
 */
public final class ScenarioRequests {

    private ScenarioRequests() {
    }

    @Schema(description = "A hypothetical change to run against current conditions")
    public record RunScenario(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 1000) String description,

            @Schema(description = "City to simulate, by slug")
            @NotBlank String citySlug,

            // @Valid on each: without it Bean Validation stops at the top
            // level and every bound declared below is decorative. A request
            // for 500 mm/h of rain would have been accepted and answered
            // confidently about conditions nothing here models.
            @Valid Weather weather,
            @Valid CityEvent event,
            @Valid Infrastructure infrastructure,
            @Valid Traffic traffic
    ) {
        /** True when nothing would change — the engine refuses these. */
        public boolean isEmpty() {
            return weather == null && event == null && infrastructure == null && traffic == null;
        }
    }

    @Schema(description = "Weather override applied city-wide")
    public record Weather(
            @Schema(description = "Rainfall in mm/h. 20 saturates the model's response.")
            @DecimalMin("0.0") @DecimalMax("50.0") Double rainIntensityMmH,

            @DecimalMin("-10.0") @DecimalMax("55.0") Double temperatureC,

            @DecimalMin("0.0") @DecimalMax("150.0") Double windSpeedKph
    ) {
    }

    @Schema(description = "A gathering in one zone")
    public record CityEvent(
            @Schema(description = "Zone the event is held in, by code")
            @NotBlank String zoneCode,

            @Schema(description = "CONCERT | SPORTS | FESTIVAL | CONFERENCE | PARADE | MARATHON")
            @NotBlank String eventType,

            @Schema(description = "Expected attendance. Drives the demand increase.")
            @Min(0) @Max(500_000) Integer expectedAttendance,

            @Schema(description = "Hours until the event starts; 0 means it is under way")
            @Min(0) @Max(72) Integer startsInHours,

            @Min(1) @Max(48) Integer durationHours
    ) {
    }

    @Schema(description = "Changes to what the road network can carry")
    public record Infrastructure(
            @Schema(description = "Zones with a closed road, by code")
            List<@NotBlank String> closedRoadZoneCodes,

            @Schema(description = "Percentage of capacity removed in the affected zones, 0-90")
            @DecimalMin("0.0") @DecimalMax("90.0") Double capacityReductionPct,

            @Schema(description = "Percentage of public transport out of service, 0-100. "
                                  + "Displaced riders become road traffic.")
            @DecimalMin("0.0") @DecimalMax("100.0") Double transitDisruptionPct
    ) {
    }

    @Schema(description = "A direct change in how many vehicles are on the road")
    public record Traffic(
            @Schema(description = "Percentage change in vehicle volume, -80 to +300")
            @DecimalMin("-80.0") @DecimalMax("300.0") Double volumeChangePct,

            @Schema(description = "Restrict to these zones; empty means city-wide")
            List<@NotBlank String> zoneCodes
    ) {
    }
}
