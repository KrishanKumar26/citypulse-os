package com.citypulse.simulation.service;

import com.citypulse.common.api.PageResponse;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.security.CurrentUser;
import com.citypulse.simulation.domain.Simulation;
import com.citypulse.simulation.domain.SimulationResult;
import com.citypulse.simulation.dto.ScenarioRequests;
import com.citypulse.simulation.dto.SimulationResponses;
import com.citypulse.simulation.repository.SimulationRepository;
import com.citypulse.telemetry.config.TelemetryProperties;
import com.citypulse.telemetry.domain.ZoneMetric;
import com.citypulse.telemetry.repository.ZoneMetricRepository;
import com.citypulse.user.domain.Permissions;
import com.citypulse.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs and stores scenarios (PRD §14).
 *
 * <p>Synchronous. A scenario over twenty zones is arithmetic over twenty rows,
 * so the honest design is to compute it in the request and return the answer —
 * a job queue would add latency, a polling endpoint and a failure mode, for
 * nothing.
 */
@Service
public class SimulationService {

    private final SimulationRepository simulationRepository;
    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final ZoneMetricRepository zoneMetricRepository;
    private final ScenarioEngine engine;
    private final TelemetryProperties telemetryProperties;
    private final CurrentUser currentUser;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public SimulationService(SimulationRepository simulationRepository,
                             CityRepository cityRepository,
                             ZoneRepository zoneRepository,
                             ZoneMetricRepository zoneMetricRepository,
                             ScenarioEngine engine,
                             TelemetryProperties telemetryProperties,
                             CurrentUser currentUser,
                             UserRepository userRepository,
                             ObjectMapper objectMapper) {
        this.simulationRepository = simulationRepository;
        this.cityRepository = cityRepository;
        this.zoneRepository = zoneRepository;
        this.zoneMetricRepository = zoneMetricRepository;
        this.engine = engine;
        this.telemetryProperties = telemetryProperties;
        this.currentUser = currentUser;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATION_CREATE + "')")
    public SimulationResponses.SimulationDetail run(ScenarioRequests.RunScenario request) {
        if (request.isEmpty()) {
            // A scenario that changes nothing would report a perfect no-op as an
            // insight. Refusing says plainly that nothing was asked.
            throw new Exceptions.BadRequest(
                    "A scenario must change something — set weather, an event, infrastructure or traffic.");
        }

        long startedAt = System.nanoTime();

        City city = cityRepository.findBySlugAndDeletedAtIsNull(request.citySlug())
                .orElseThrow(() -> new Exceptions.NotFound("City", request.citySlug()));

        List<Zone> zones = zoneRepository.findByCity(city.getId(), true);
        Instant notBefore = Instant.now().minus(telemetryProperties.maxAge());

        Map<Long, ZoneMetric> latest = zoneMetricRepository
                .findLatestPerZoneInCity(city.getId(), notBefore).stream()
                .collect(Collectors.toMap(ZoneMetric::getZoneId, Function.identity(), (a, b) -> a));

        if (latest.isEmpty()) {
            // Simulating from an assumed starting point would give a confident
            // before/after where the "before" was invented.
            throw new Exceptions.BadRequest(
                    "No recent telemetry for " + city.getName()
                    + ". A scenario needs observed conditions to depart from.");
        }

        Map<String, Zone> zonesByCode = zones.stream()
                .collect(Collectors.toMap(Zone::getCode, Function.identity()));

        List<ScenarioEngine.Baseline> baselines = zones.stream()
                .map(zone -> toBaseline(zone, latest.get(zone.getId())))
                .filter(java.util.Objects::nonNull)
                .toList();

        if (request.event() != null && !zonesByCode.containsKey(request.event().zoneCode())) {
            throw new Exceptions.BadRequest(
                    "Unknown zone '" + request.event().zoneCode() + "' for the event.");
        }

        List<ScenarioEngine.Outcome> outcomes = engine.run(baselines, request);

        Instant baselineWindow = latest.values().stream()
                .map(ZoneMetric::getWindowStart)
                .max(Instant::compareTo)
                .orElseThrow();

        Simulation simulation = persist(request, city, baselineWindow, outcomes, zonesByCode,
                (int) ((System.nanoTime() - startedAt) / 1_000_000));

        return toDetail(simulation);
    }

    private ScenarioEngine.Baseline toBaseline(Zone zone, ZoneMetric metric) {
        if (metric == null || metric.getOccupancyRatio() == null) {
            return null;
        }
        // Rated capacity is the denominator occupancy is expressed against. A
        // zone without one cannot be simulated, because there is nothing to
        // express the counterfactual load as a fraction of.
        if (zone.getRoadCapacityVph() == null || zone.getRoadCapacityVph() <= 0) {
            return null;
        }

        return new ScenarioEngine.Baseline(
                zone.getCode(), zone.getName(),
                zone.getCenterLatitude().doubleValue(), zone.getCenterLongitude().doubleValue(),
                zone.getRoadCapacityVph().doubleValue(),
                metric.getOccupancyRatio().doubleValue(),
                metric.getAverageSpeedKph() == null ? null : metric.getAverageSpeedKph().doubleValue(),
                metric.getVehicleCount(),
                metric.getAqi(),
                metric.getActiveIncidents() == null ? 0 : metric.getActiveIncidents(),
                metric.getPrecipitationMmH() == null ? null : metric.getPrecipitationMmH().doubleValue(),
                metric.getRiskScore() == null ? null : metric.getRiskScore().doubleValue());
    }

    private Simulation persist(ScenarioRequests.RunScenario request, City city,
                               Instant baselineWindow, List<ScenarioEngine.Outcome> outcomes,
                               Map<String, Zone> zonesByCode, int computedMs) {
        Simulation simulation = new Simulation();
        simulation.setCity(city);
        simulation.setName(request.name());
        simulation.setDescription(request.description());
        simulation.setBaselineWindow(baselineWindow);
        simulation.setEngineVersion(ScenarioEngine.VERSION);
        simulation.setComputedMs(computedMs);
        currentUser.find().flatMap(p -> userRepository.findByUidAndDeletedAtIsNull(p.userUid()))
                .ifPresent(simulation::setCreatedBy);

        try {
            simulation.setScenario(objectMapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the scenario", e);
        }

        // City-wide figures are averaged over affected zones only. Including
        // untouched zones would dilute the headline toward zero and make a
        // severe local impact look mild.
        List<ScenarioEngine.Outcome> affected = outcomes.stream()
                .filter(o -> Math.abs(o.occupancyChangePct()) > 0.01)
                .toList();

        simulation.setZonesAffected(affected.size());
        simulation.setTrafficChangePct(average(affected, ScenarioEngine.Outcome::occupancyChangePct));
        simulation.setCrowdChangePct(average(affected, ScenarioEngine.Outcome::crowdChangePct));
        simulation.setParkingChangePct(average(affected, ScenarioEngine.Outcome::parkingChangePct));
        simulation.setDelayChangeMin(average(affected, ScenarioEngine.Outcome::delayChangeMinutes));
        simulation.setBaselineRisk(average(outcomes,
                o -> o.baseline().riskScore() == null ? 0.0 : o.baseline().riskScore()));
        simulation.setSimulatedRisk(average(outcomes,
                o -> o.simulatedRiskScore() == null ? 0.0 : o.simulatedRiskScore()));

        try {
            simulation.setRecommendations(objectMapper.writeValueAsString(
                    Recommendations.forOutcomes(outcomes)));
        } catch (Exception e) {
            simulation.setRecommendations("[]");
        }

        for (ScenarioEngine.Outcome outcome : outcomes) {
            Zone zone = zonesByCode.get(outcome.zoneCode());
            if (zone == null) {
                continue;
            }
            SimulationResult result = new SimulationResult();
            result.setSimulation(simulation);
            result.setZone(zone);
            result.setBaselineOccupancy(decimal(outcome.baseline().occupancyRatio(), 4));
            result.setBaselineSpeedKph(decimal(outcome.baseline().speedKph(), 2));
            result.setBaselineVehicleCount(outcome.baseline().vehicleCount());
            result.setBaselineRiskScore(decimal(outcome.baseline().riskScore(), 2));
            result.setBaselineCongestion(outcome.baseline().occupancyRatio() == null ? null
                    : CityPhysics.congestionLevel(outcome.baseline().occupancyRatio()));
            result.setSimulatedOccupancy(decimal(outcome.simulatedOccupancy(), 4));
            result.setSimulatedSpeedKph(decimal(outcome.simulatedSpeedKph(), 2));
            result.setSimulatedVehicleCount(outcome.simulatedVehicleCount());
            result.setSimulatedRiskScore(decimal(outcome.simulatedRiskScore(), 2));
            result.setSimulatedCongestion(outcome.simulatedCongestion());
            result.setDelayChangeMin(decimal(outcome.delayChangeMinutes(), 2));
            result.setParkingChangePct(decimal(outcome.parkingChangePct(), 2));
            result.setCrowdChangePct(decimal(outcome.crowdChangePct(), 2));
            result.setImpactSource(outcome.impactSource());
            simulation.getResults().add(result);
        }

        return simulationRepository.save(simulation);
    }

    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATION_READ + "')")
    public SimulationResponses.SimulationDetail get(UUID uid) {
        return toDetail(simulationRepository.findByUid(uid)
                .orElseThrow(() -> new Exceptions.NotFound("Simulation", uid)));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.SIMULATION_READ + "')")
    public PageResponse<SimulationResponses.SimulationSummary> list(String citySlug, Pageable pageable) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(citySlug)
                .orElseThrow(() -> new Exceptions.NotFound("City", citySlug));

        return PageResponse.from(
                simulationRepository.findForCity(city.getId(), pageable),
                s -> new SimulationResponses.SimulationSummary(
                        s.getUid().toString(), s.getName(), s.getDescription(),
                        s.getCreatedAt(), s.getBaselineWindow(), s.getEngineVersion(),
                        s.getTrafficChangePct(), s.getDelayChangeMin(),
                        s.getBaselineRisk(), s.getSimulatedRisk(), s.getZonesAffected()));
    }

    private SimulationResponses.SimulationDetail toDetail(Simulation s) {
        List<SimulationResponses.ZoneImpact> impacts = s.getResults().stream()
                .map(r -> new SimulationResponses.ZoneImpact(
                        r.getZone().getUid().toString(), r.getZone().getCode(), r.getZone().getName(),
                        r.getZone().getCenterLatitude(), r.getZone().getCenterLongitude(),
                        r.getBaselineOccupancy(), r.getSimulatedOccupancy(),
                        r.getBaselineSpeedKph(), r.getSimulatedSpeedKph(),
                        r.getBaselineRiskScore(), r.getSimulatedRiskScore(),
                        r.getBaselineCongestion(), r.getSimulatedCongestion(),
                        r.getDelayChangeMin(), r.getParkingChangePct(), r.getCrowdChangePct(),
                        r.getImpactSource()))
                // Worst first: an operator scanning this wants the zones that
                // need attention at the top.
                .sorted((a, b) -> b.simulatedRiskScore().compareTo(a.simulatedRiskScore()))
                .toList();

        List<SimulationResponses.Recommendation> recommendations;
        try {
            recommendations = objectMapper.readValue(s.getRecommendations(),
                    objectMapper.getTypeFactory().constructCollectionType(
                            List.class, SimulationResponses.Recommendation.class));
        } catch (Exception e) {
            recommendations = List.of();
        }

        return new SimulationResponses.SimulationDetail(
                s.getUid().toString(), s.getName(), s.getDescription(),
                s.getCity().getSlug(), s.getCreatedAt(),
                s.getBaselineWindow(), s.getEngineVersion(), s.getComputedMs(),
                s.getTrafficChangePct(), s.getCrowdChangePct(), s.getParkingChangePct(),
                s.getDelayChangeMin(), s.getBaselineRisk(), s.getSimulatedRisk(),
                s.getZonesAffected(), impacts, recommendations);
    }

    private BigDecimal average(List<ScenarioEngine.Outcome> outcomes,
                               java.util.function.ToDoubleFunction<ScenarioEngine.Outcome> field) {
        if (outcomes.isEmpty()) {
            return BigDecimal.ZERO;
        }
        double mean = outcomes.stream().mapToDouble(field).average().orElse(0.0);
        return BigDecimal.valueOf(mean).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Double value, int scale) {
        return value == null ? null : BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }
}
