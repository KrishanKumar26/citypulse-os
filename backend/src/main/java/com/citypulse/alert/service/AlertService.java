package com.citypulse.alert.service;

import com.citypulse.common.time.Timestamps;
import com.citypulse.alert.domain.Alert;
import com.citypulse.alert.domain.AlertStatus;
import com.citypulse.alert.dto.AlertResponses;
import com.citypulse.alert.repository.AlertRepository;
import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.service.AuditService;
import com.citypulse.common.api.PageResponse;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.domain.Permissions;
import com.citypulse.user.domain.User;
import com.citypulse.user.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Reading and working alerts (PRD §17).
 *
 * <p>Raising is not here — that belongs to {@link AlertEngine}, which runs on a
 * schedule with no user in context. This service covers what a person does to an
 * alert once it exists, which is why every method audits: acknowledging or
 * resolving is an operator decision about a city condition, and PRD §30 requires
 * those to be attributable.
 */
@Service
public class AlertService {

    private final AlertRepository alertRepository;
    private final CityRepository cityRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final AuditService auditService;

    public AlertService(AlertRepository alertRepository,
                        CityRepository cityRepository,
                        UserRepository userRepository,
                        CurrentUser currentUser,
                        AuditService auditService) {
        this.alertRepository = alertRepository;
        this.cityRepository = cityRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_READ + "')")
    public PageResponse<AlertResponses.AlertDetail> list(UUID cityUid,
                                                         AlertStatus status,
                                                         boolean openOnly,
                                                         Pageable pageable) {
        Long cityId = cityUid == null ? null : cityRepository.findByUidAndDeletedAtIsNull(cityUid)
                .orElseThrow(() -> new Exceptions.NotFound("City", cityUid))
                .getId();

        Page<Alert> page = alertRepository.search(cityId, status, openOnly, pageable);
        return PageResponse.from(page, this::toDetail);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_READ + "')")
    public AlertResponses.AlertDetail get(UUID alertUid) {
        return toDetail(require(alertUid));
    }

    /**
     * Moves an alert to a new state.
     *
     * <p>Transitions out of RESOLVED are refused. Reopening would make the
     * recorded resolution time false, and a condition that recurs deserves its
     * own alert with its own raised-at rather than inheriting an old one's
     * history.
     */
    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_MANAGE + "')")
    public AlertResponses.AlertDetail transition(UUID alertUid, AlertStatus next, String note) {
        Alert alert = require(alertUid);
        // Captured before mutating: the audit entry records where the alert came
        // from, and reading it back off the entity afterwards would log
        // "RESOLVED → RESOLVED".
        AlertStatus previous = alert.getStatus();
        if (!previous.canTransitionTo(next)) {
            throw new Exceptions.BadRequest(
                    "Cannot move an alert from %s to %s".formatted(previous, next));
        }

        User actor = userRepository.findByUidAndDeletedAtIsNull(currentUser.require().userUid())
                .orElseThrow(() -> new Exceptions.NotFound("User", currentUser.require().userUid()));
        Instant now = Timestamps.now();

        switch (next) {
            case ACKNOWLEDGED, INVESTIGATING -> {
                // Acknowledgement is recorded once. Someone moving an alert on to
                // INVESTIGATING has not re-acknowledged it, and overwriting the
                // original timestamp would lose who saw it first.
                if (alert.getAcknowledgedAt() == null) {
                    alert.setAcknowledgedAt(now);
                    alert.setAcknowledgedBy(actor);
                }
            }
            case RESOLVED -> {
                alert.setResolvedAt(now);
                alert.setResolvedBy(actor);
                alert.setResolutionNote(note);
                if (alert.getAcknowledgedAt() == null) {
                    // Resolving straight from NEW is legitimate — a false alarm
                    // dismissed on sight — but it still counts as having been seen.
                    alert.setAcknowledgedAt(now);
                    alert.setAcknowledgedBy(actor);
                }
            }
            default -> throw new Exceptions.BadRequest("Unsupported target status: " + next);
        }

        alert.setStatus(next);
        alert.setUpdatedAt(now);
        alertRepository.save(alert);

        AuditAction action = switch (next) {
            case ACKNOWLEDGED -> AuditAction.ALERT_ACKNOWLEDGED;
            case INVESTIGATING -> AuditAction.ALERT_INVESTIGATING;
            case RESOLVED -> AuditAction.ALERT_RESOLVED;
            default -> throw new Exceptions.BadRequest("Unsupported target status: " + next);
        };
        auditService.recordResourceChange(action, actor.getId(), actor.getEmail(),
                "ALERT", alert.getUid().toString(),
                "%s → %s%s".formatted(previous, next,
                        note == null || note.isBlank() ? "" : " (" + note + ")"));

        return toDetail(alert);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ALERT_READ + "')")
    public AlertResponses.AlertSummary summary(UUID cityUid) {
        Long cityId = cityUid == null ? null : cityRepository.findByUidAndDeletedAtIsNull(cityUid)
                .orElseThrow(() -> new Exceptions.NotFound("City", cityUid))
                .getId();

        Page<Alert> open = alertRepository.search(cityId, null, true, Pageable.unpaged());
        var alerts = open.getContent();

        return new AlertResponses.AlertSummary(
                alerts.size(),
                (int) alerts.stream().filter(a -> a.getSeverity().name().equals("CRITICAL")).count(),
                (int) alerts.stream().filter(a -> a.getSeverity().name().equals("HIGH")).count(),
                (int) alerts.stream().filter(a -> a.getSeverity().name().equals("MEDIUM")).count(),
                (int) alerts.stream().filter(a -> a.getSeverity().name().equals("LOW")).count(),
                (int) alerts.stream().filter(a -> a.getAcknowledgedAt() == null).count()
        );
    }

    private Alert require(UUID alertUid) {
        return alertRepository.findByUid(alertUid)
                .orElseThrow(() -> new Exceptions.NotFound("Alert", alertUid));
    }

    private AlertResponses.AlertDetail toDetail(Alert alert) {
        return new AlertResponses.AlertDetail(
                alert.getUid().toString(),
                alert.getAlertType().name(),
                alert.getSeverity().name(),
                alert.getStatus().name(),
                alert.getTitle(),
                alert.getDescription(),
                alert.getZone() == null ? null : alert.getZone().getUid().toString(),
                alert.getZone() == null ? null : alert.getZone().getCode(),
                alert.getZone() == null ? null : alert.getZone().getName(),
                alert.getCity() == null ? null : alert.getCity().getUid().toString(),
                alert.getCity() == null ? null : alert.getCity().getSlug(),
                alert.getRuleCode(),
                alert.getMetricName(),
                alert.getObservedValue(),
                alert.getThresholdValue(),
                alert.getZoneMetricWindowStart(),
                alert.getRecommendedAction(),
                alert.getRaisedAt(),
                alert.getAcknowledgedAt(),
                alert.getAcknowledgedBy() == null ? null : alert.getAcknowledgedBy().getEmail(),
                alert.getResolvedAt(),
                alert.getResolvedBy() == null ? null : alert.getResolvedBy().getEmail(),
                alert.getResolutionNote(),
                alert.isDemoData()
        );
    }
}
