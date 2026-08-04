package com.citypulse.audit.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.dto.AuditLogResponse;
import com.citypulse.audit.repository.AuditLogRepository;
import com.citypulse.common.api.PageResponse;
import com.citypulse.user.domain.Permissions;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Read side of the audit log, kept separate from {@link AuditService} so the
 * write path has no query surface and the read path cannot accidentally acquire
 * a mutation method.
 */
@Service
public class AuditQueryService {

    private final AuditLogRepository repository;

    public AuditQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.AUDIT_READ + "')")
    public PageResponse<AuditLogResponse> search(AuditAction action, Instant from, Instant to,
                                                 int page, int size) {
        var pageable = PageRequest.of(page, size);
        return PageResponse.from(
                repository.search(action, null, from, to, pageable),
                AuditLogResponse::from);
    }
}
