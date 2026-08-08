package com.citypulse.response.service;

import com.citypulse.alert.domain.Alert;
import com.citypulse.alert.repository.AlertRepository;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.common.time.Timestamps;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.intervention.repository.InterventionRepository;
import com.citypulse.response.domain.ResponsePlan;
import com.citypulse.response.domain.ResponsePlanStep;
import com.citypulse.response.dto.ResponseRequests;
import com.citypulse.response.dto.ResponseResponses;
import com.citypulse.response.repository.ResponsePlanRepository;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.domain.User;
import com.citypulse.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Response plans: what we intend to do, before we do it (PRD §16).
 *
 * <p>The rule this service exists to enforce is what it will not write. Exactly
 * one step may come from the platform — the {@code recommendedAction} an alert
 * rule attached when it fired, which a real rule computed from a real reading.
 * Every other step is text a person typed, and the step records which it was.
 *
 * <p>Presenting the two in the same voice is how generated text starts being
 * trusted as considered judgement, and a response plan is the screen where that
 * gets acted on rather than merely read.
 */
@Service
public class ResponsePlanService {

    private final ResponsePlanRepository repository;
    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final AlertRepository alertRepository;
    private final InterventionRepository interventionRepository;
    private final UserRepository userRepository;
    private final CurrentUser currentUser;

    public ResponsePlanService(ResponsePlanRepository repository,
                               CityRepository cityRepository,
                               ZoneRepository zoneRepository,
                               AlertRepository alertRepository,
                               InterventionRepository interventionRepository,
                               UserRepository userRepository,
                               CurrentUser currentUser) {
        this.repository = repository;
        this.cityRepository = cityRepository;
        this.zoneRepository = zoneRepository;
        this.alertRepository = alertRepository;
        this.interventionRepository = interventionRepository;
        this.userRepository = userRepository;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ResponseResponses.PlanDetail> list(String citySlug, boolean openOnly) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(citySlug)
                .orElseThrow(() -> new Exceptions.NotFound("City", citySlug));
        return repository.findForCity(city.getId(), openOnly).stream().map(this::toDetail).toList();
    }

    @Transactional
    public ResponseResponses.PlanDetail create(ResponseRequests.Create request) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(request.citySlug())
                .orElseThrow(() -> new Exceptions.NotFound("City", request.citySlug()));
        User author = requireCaller();

        ResponsePlan plan = new ResponsePlan();
        plan.setTitle(request.title());
        plan.setSummary(request.summary());
        plan.setCity(city);
        plan.setPriority(request.priority() == null ? "MEDIUM" : request.priority());
        plan.setCreatedBy(author);
        plan.setDemoData(city.isDemoData());

        if (request.zoneId() != null) {
            Zone zone = zoneRepository.findByUidAndDeletedAtIsNull(request.zoneId())
                    .orElseThrow(() -> new Exceptions.NotFound("Zone", request.zoneId()));
            if (!zone.getCity().getId().equals(city.getId())) {
                throw new Exceptions.BadRequest("Zone does not belong to " + city.getSlug());
            }
            plan.setZone(zone);
        }

        List<ResponsePlanStep> steps = new ArrayList<>();
        int position = 0;

        if (request.alertId() != null) {
            Alert alert = alertRepository.findByUid(request.alertId())
                    .orElseThrow(() -> new Exceptions.NotFound("Alert", request.alertId()));
            plan.setAlert(alert);

            // The one step the platform may contribute. It is not advice this
            // service composed — it is the string a rule attached when it fired,
            // copied verbatim and flagged as such.
            if (alert.getRecommendedAction() != null && !alert.getRecommendedAction().isBlank()) {
                ResponsePlanStep fromRule = new ResponsePlanStep();
                fromRule.setPlan(plan);
                fromRule.setPosition(position++);
                fromRule.setInstruction(alert.getRecommendedAction());
                fromRule.setFromAlertRule(true);
                steps.add(fromRule);
            }
        }

        for (ResponseRequests.Step requested : request.steps()) {
            ResponsePlanStep step = new ResponsePlanStep();
            step.setPlan(plan);
            step.setPosition(position++);
            step.setInstruction(requested.instruction());
            step.setFromAlertRule(false);
            steps.add(step);
        }

        plan.getSteps().addAll(steps);
        return toDetail(repository.save(plan));
    }

    @Transactional
    public ResponseResponses.PlanDetail updateStep(UUID planId, UUID stepId,
                                                   ResponseRequests.UpdateStep request) {
        ResponsePlan plan = repository.findByUidAndDeletedAtIsNull(planId)
                .orElseThrow(() -> new Exceptions.NotFound("Response plan", planId));

        ResponsePlanStep step = plan.getSteps().stream()
                .filter(s -> s.getUid().equals(stepId))
                .findFirst()
                .orElseThrow(() -> new Exceptions.NotFound("Step", stepId));

        // Enforced here as well as by the check constraint, so the caller gets a
        // 400 explaining what is missing rather than a 500 from the database.
        if (("BLOCKED".equals(request.status()) || "SKIPPED".equals(request.status()))
                && (request.note() == null || request.note().isBlank())) {
            throw new Exceptions.BadRequest(
                    "A blocked or skipped step needs a note saying why");
        }

        step.setStatus(request.status());
        step.setNote(request.note());

        if ("DONE".equals(request.status())) {
            step.setCompletedAt(Timestamps.now());
            step.setCompletedBy(requireCaller());
            if (request.interventionId() != null) {
                step.setIntervention(
                        interventionRepository.findByUidAndDeletedAtIsNull(request.interventionId())
                                .orElseThrow(() -> new Exceptions.NotFound(
                                        "Intervention", request.interventionId())));
            }
        } else {
            // Reopening a step must clear its completion, or the plan would show
            // a pending step that someone is recorded as having finished.
            step.setCompletedAt(null);
            step.setCompletedBy(null);
        }

        return toDetail(repository.save(plan));
    }

    @Transactional
    public ResponseResponses.PlanDetail updatePlan(UUID planId, ResponseRequests.UpdatePlan request) {
        ResponsePlan plan = repository.findByUidAndDeletedAtIsNull(planId)
                .orElseThrow(() -> new Exceptions.NotFound("Response plan", planId));

        if (request.status() != null) {
            String previous = plan.getStatus();
            plan.setStatus(request.status());

            if ("ACTIVE".equals(request.status()) && plan.getActivatedAt() == null) {
                plan.setActivatedAt(Timestamps.now());
            }
            if ("COMPLETED".equals(request.status()) || "CANCELLED".equals(request.status())) {
                plan.setClosedAt(Timestamps.now());
            } else if ("COMPLETED".equals(previous) || "CANCELLED".equals(previous)) {
                // Reopened. The close time has to go with it: a plan that is
                // open again did not close when the column says it did.
                plan.setClosedAt(null);
            }
        }

        if (request.assignedTo() != null) {
            plan.setAssignedTo(userRepository.findByUidAndDeletedAtIsNull(request.assignedTo())
                    .orElseThrow(() -> new Exceptions.NotFound("User", request.assignedTo())));
        }

        return toDetail(repository.save(plan));
    }

    private User requireCaller() {
        return userRepository.findByUidAndDeletedAtIsNull(currentUser.require().userUid())
                .orElseThrow(() -> new Exceptions.NotFound("User", currentUser.require().userUid()));
    }

    private ResponseResponses.PlanDetail toDetail(ResponsePlan plan) {
        List<ResponseResponses.StepDetail> steps = plan.getSteps().stream()
                .sorted(java.util.Comparator.comparingInt(ResponsePlanStep::getPosition))
                .map(s -> new ResponseResponses.StepDetail(
                        s.getUid().toString(),
                        s.getPosition(),
                        s.getInstruction(),
                        s.isFromAlertRule(),
                        s.getStatus(),
                        s.getNote(),
                        s.getCompletedAt(),
                        s.getCompletedBy() == null ? null : s.getCompletedBy().getEmail(),
                        s.getIntervention() == null ? null : s.getIntervention().getUid().toString()))
                .toList();

        return new ResponseResponses.PlanDetail(
                plan.getUid().toString(),
                plan.getTitle(),
                plan.getSummary(),
                plan.getCity().getSlug(),
                plan.getZone() == null ? null : plan.getZone().getUid().toString(),
                plan.getZone() == null ? null : plan.getZone().getName(),
                plan.getAlert() == null ? null : plan.getAlert().getUid().toString(),
                plan.getAlert() == null ? null : plan.getAlert().getTitle(),
                plan.getPriority(),
                plan.getStatus(),
                plan.getCreatedBy().getEmail(),
                plan.getAssignedTo() == null ? null : plan.getAssignedTo().getEmail(),
                plan.getActivatedAt(),
                plan.getClosedAt(),
                plan.getCreatedAt(),
                (int) steps.stream().filter(s -> "DONE".equals(s.status())).count(),
                steps.size(),
                (int) steps.stream().filter(s -> "BLOCKED".equals(s.status())).count(),
                steps,
                plan.isDemoData());
    }
}
