package com.citypulse.audit.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Append-only record of a security-sensitive action (PRD §30, docs/SECURITY.md §6).
 *
 * <p>There is deliberately no update or delete path anywhere in the application
 * for this table. Entries must never contain passwords, tokens, or token hashes.
 */
@Entity
@Table(name = "audit_logs", indexes = {
        @Index(name = "idx_audit_logs_actor_id", columnList = "actor_id"),
        @Index(name = "idx_audit_logs_action", columnList = "action"),
        @Index(name = "idx_audit_logs_occurred_at", columnList = "occurred_at"),
        @Index(name = "idx_audit_logs_resource", columnList = "resource_type,resource_id")
})
@Getter
@Setter
@NoArgsConstructor
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for actions taken before authentication, e.g. a failed login. */
    @Column(name = "actor_id")
    private Long actorId;

    /** Captured at write time so the entry stays meaningful if the user is renamed. */
    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 48)
    private AuditAction action;

    @Column(name = "resource_type", length = 48)
    private String resourceType;

    @Column(name = "resource_id", length = 64)
    private String resourceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 16)
    private AuditOutcome outcome;

    /** Short, non-sensitive context. Never credentials. */
    @Column(name = "detail", length = 512)
    private String detail;

    @Column(name = "client_ip", length = 45)
    private String clientIp;

    @Column(name = "user_agent", length = 255)
    private String userAgent;

    @Column(name = "request_id", length = 64)
    private String requestId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();
}
