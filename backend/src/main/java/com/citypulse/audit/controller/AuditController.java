package com.citypulse.audit.controller;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.dto.AuditLogResponse;
import com.citypulse.audit.service.AuditQueryService;
import com.citypulse.common.api.ApiResponse;
import com.citypulse.common.api.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Validated
@Tag(name = "Audit", description = "Security-sensitive action history")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 100;

    private final AuditQueryService auditQueryService;

    public AuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @Operation(summary = "Search the audit log",
            description = "Requires audit:read. The log is append-only; there is no write or delete endpoint.")
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> search(
            @RequestParam(required = false) AuditAction action,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return ResponseEntity.ok(ApiResponse.ok(auditQueryService.search(action, from, to, page, size)));
    }
}
