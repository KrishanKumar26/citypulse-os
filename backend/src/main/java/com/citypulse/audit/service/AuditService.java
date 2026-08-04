package com.citypulse.audit.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.domain.AuditLog;
import com.citypulse.audit.domain.AuditOutcome;
import com.citypulse.audit.repository.AuditLogRepository;
import com.citypulse.common.web.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes audit entries (PRD §30, docs/SECURITY.md §6).
 *
 * <p>Every write runs in {@code REQUIRES_NEW}. A failed login must still be
 * recorded even though the surrounding transaction is rolled back, and an audit
 * write must never be the reason a business transaction fails — hence the
 * separate transaction plus the swallow-and-log on error.
 *
 * <p>Entries carry no credentials. {@code detail} is written by callers and is
 * reviewed on that basis.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final int MAX_DETAIL_LENGTH = 512;
    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AuditAction action, AuditOutcome outcome, Long actorId, String actorEmail,
                       String resourceType, String resourceId, String detail) {
        try {
            AuditLog entry = new AuditLog();
            entry.setAction(action);
            entry.setOutcome(outcome);
            entry.setActorId(actorId);
            entry.setActorEmail(actorEmail);
            entry.setResourceType(resourceType);
            entry.setResourceId(resourceId);
            entry.setDetail(truncate(detail, MAX_DETAIL_LENGTH));
            entry.setRequestId(MDC.get(RequestIdFilter.REQUEST_ID_KEY));

            HttpServletRequest request = currentRequest();
            if (request != null) {
                entry.setClientIp(clientIp(request));
                entry.setUserAgent(truncate(request.getHeader("User-Agent"), MAX_USER_AGENT_LENGTH));
            }

            repository.save(entry);
        } catch (Exception ex) {
            // An audit failure is a defect worth alerting on, but it must not
            // turn a successful login into a 500.
            log.error("Failed to write audit entry for action {}", action, ex);
        }
    }

    public void recordSuccess(AuditAction action, Long actorId, String actorEmail, String detail) {
        record(action, AuditOutcome.SUCCESS, actorId, actorEmail, null, null, detail);
    }

    public void recordFailure(AuditAction action, String actorEmail, String detail) {
        record(action, AuditOutcome.FAILURE, null, actorEmail, null, null, detail);
    }

    public void recordResourceChange(AuditAction action, Long actorId, String actorEmail,
                                     String resourceType, String resourceId, String detail) {
        record(action, AuditOutcome.SUCCESS, actorId, actorEmail, resourceType, resourceId, detail);
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest()
                : null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return truncate(forwarded.split(",")[0].trim(), 45);
        }
        return truncate(request.getRemoteAddr(), 45);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
