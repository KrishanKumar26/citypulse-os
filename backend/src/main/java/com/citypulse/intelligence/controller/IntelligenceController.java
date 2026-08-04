package com.citypulse.intelligence.controller;

import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.api.PageResponse;
import com.citypulse.intelligence.dto.IntelligenceResponses;
import com.citypulse.intelligence.service.IntelligenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Anomalies, correlations and City Memory (PRD §12, §13, §16).
 *
 * <p>Every response carries the evidence behind it: an anomaly names the
 * baseline it departed from, a correlation names the window counts it was
 * measured over, and a memory recall either cites enough past situations or
 * states plainly that it cannot.
 */
@RestController
@RequestMapping("/api/v1/anomalies")
@Validated
@Tag(name = "Intelligence", description = "Anomalies, measured correlations and City Memory")
public class IntelligenceController {

    private final IntelligenceService intelligenceService;

    public IntelligenceController(IntelligenceService intelligenceService) {
        this.intelligenceService = intelligenceService;
    }

    @GetMapping
    @Operation(summary = "Anomalies for a city",
            description = "Requires anomaly:read. Ordered by how far from normal they were. "
                          + "An anomaly is a departure from what this zone usually does at this "
                          + "hour of the week — not a fixed threshold, which is what alerts use.")
    public ResponseEntity<ApiResponse<PageResponse<IntelligenceResponses.AnomalyDetail>>> anomalies(
            @RequestParam String citySlug,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false, defaultValue = "24") Integer hours,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(
                intelligenceService.anomalies(citySlug, severity, hours, pageable)));
    }

    @GetMapping("/correlations")
    @Operation(summary = "Measured co-occurrence between conditions",
            description = "Requires analytics:read. Lift above 1 means the pairing is more common "
                          + "than chance. These are measurements of co-occurrence, never claims "
                          + "of causation — the response says so explicitly.")
    public ResponseEntity<ApiResponse<List<IntelligenceResponses.Correlation>>> correlations(
            @RequestParam String citySlug) {
        return ResponseEntity.ok(ApiResponse.ok(intelligenceService.correlations(citySlug)));
    }

    @GetMapping("/memory")
    @Operation(summary = "What historically followed conditions like these",
            description = "Requires analytics:read. Matches the fingerprint against past "
                          + "situations and reports the outcomes that actually followed. When too "
                          + "few comparable situations exist, says so rather than reporting a "
                          + "median over a handful.")
    public ResponseEntity<ApiResponse<IntelligenceResponses.MemoryRecall>> memory(
            @RequestParam String citySlug,
            @RequestParam(defaultValue = "NONE") String rainBand,
            @RequestParam(defaultValue = "WEEKDAY") String dayType,
            @RequestParam(defaultValue = "EVENING_PEAK") String hourBand,
            @RequestParam(defaultValue = "false") boolean hadEvent,
            @RequestParam(defaultValue = "NONE") String incidentBand) {
        return ResponseEntity.ok(ApiResponse.ok(intelligenceService.recall(
                citySlug, rainBand, dayType, hourBand, hadEvent, incidentBand)));
    }

    @GetMapping("/insights")
    @Operation(summary = "Everything the platform can currently say about a city",
            description = "Requires analytics:read. Recent anomalies, measured correlations, and "
                          + "what history says about the conditions in place right now.")
    public ResponseEntity<ApiResponse<IntelligenceResponses.InsightsSummary>> insights(
            @RequestParam String citySlug) {
        return ResponseEntity.ok(ApiResponse.ok(intelligenceService.summary(citySlug)));
    }
}
