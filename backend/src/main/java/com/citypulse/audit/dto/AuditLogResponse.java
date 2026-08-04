package com.citypulse.audit.dto;

import com.citypulse.audit.domain.AuditLog;

import java.time.Instant;

/**
 * An audit entry as returned by the API. {@code actorId} is not exposed —
 * internal identifiers stay internal, and the recorded email identifies the
 * actor adequately for review.
 */
public record AuditLogResponse(
        Long id,
        String actorEmail,
        String action,
        String resourceType,
        String resourceId,
        String outcome,
        String detail,
        String clientIp,
        String requestId,
        Instant occurredAt
) {

    public static AuditLogResponse from(AuditLog entry) {
        return new AuditLogResponse(
                entry.getId(),
                entry.getActorEmail(),
                entry.getAction().name(),
                entry.getResourceType(),
                entry.getResourceId(),
                entry.getOutcome().name(),
                entry.getDetail(),
                entry.getClientIp(),
                entry.getRequestId(),
                entry.getOccurredAt()
        );
    }
}
