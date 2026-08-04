package com.citypulse.geo.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.service.AuditService;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.dto.GeoMapper;
import com.citypulse.geo.dto.GeoRequests;
import com.citypulse.geo.dto.GeoResponses;
import com.citypulse.geo.repository.CityRepository;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.domain.Permissions;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CityService {

    private final CityRepository cityRepository;
    private final ZoneRepository zoneRepository;
    private final GeoMapper mapper;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public CityService(CityRepository cityRepository,
                       ZoneRepository zoneRepository,
                       GeoMapper mapper,
                       AuditService auditService,
                       CurrentUser currentUser) {
        this.cityRepository = cityRepository;
        this.zoneRepository = zoneRepository;
        this.mapper = mapper;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    /**
     * Zone counts are resolved with one grouped query for the whole list, not one
     * per city — the difference between 2 queries and N+1 on the city selector,
     * which loads on every page (PRD §44).
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.CITY_READ + "')")
    public List<GeoResponses.CityResponse> list(boolean activeOnly) {
        List<City> cities = activeOnly
                ? cityRepository.findByActiveTrueAndDeletedAtIsNullOrderByNameAsc()
                : cityRepository.findByDeletedAtIsNullOrderByNameAsc();

        if (cities.isEmpty()) {
            return List.of();
        }

        Map<Long, Long> zoneCounts = new HashMap<>();
        cityRepository.countActiveZonesByCityIds(cities.stream().map(City::getId).toList())
                .forEach(row -> zoneCounts.put((Long) row[0], (Long) row[1]));

        return cities.stream()
                .map(city -> mapper.toCity(city, zoneCounts.getOrDefault(city.getId(), 0L)))
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.CITY_READ + "')")
    public GeoResponses.CityResponse getByUid(UUID uid) {
        City city = requireCity(uid);
        return mapper.toCity(city, zoneRepository.countByCityIdAndDeletedAtIsNull(city.getId()));
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.CITY_READ + "')")
    public GeoResponses.CityResponse getBySlug(String slug) {
        City city = cityRepository.findBySlugAndDeletedAtIsNull(slug)
                .orElseThrow(() -> new Exceptions.NotFound("City", slug));
        return mapper.toCity(city, zoneRepository.countByCityIdAndDeletedAtIsNull(city.getId()));
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.CITY_WRITE + "')")
    public GeoResponses.CityResponse create(GeoRequests.CreateCity request) {
        if (cityRepository.existsBySlugAndDeletedAtIsNull(request.slug())) {
            throw new Exceptions.Conflict("A city with slug '%s' already exists".formatted(request.slug()));
        }

        City city = new City();
        city.setSlug(request.slug());
        city.setName(request.name().trim());
        city.setCountry(request.country().trim());
        city.setCountryCode(request.countryCode());
        city.setTimezone(request.timezone());
        city.setCenterLatitude(request.centerLatitude());
        city.setCenterLongitude(request.centerLongitude());
        city.setDefaultZoom(request.defaultZoom() != null ? request.defaultZoom() : 11);
        city.setPopulation(request.population());
        city.setAreaSqKm(request.areaSqKm());
        // Default to demo when unspecified: mislabelling synthetic data as live is
        // the failure that matters, so it is the one the default avoids.
        city.setDemoData(request.demoData() == null || request.demoData());

        City saved = cityRepository.save(city);
        audit(AuditAction.CITY_CREATED, saved, "Created city " + saved.getSlug());
        return mapper.toCity(saved, 0L);
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.CITY_WRITE + "')")
    public GeoResponses.CityResponse update(UUID uid, GeoRequests.UpdateCity request) {
        City city = requireCity(uid);

        // The slug is intentionally immutable: it appears in saved URLs and in
        // partition paths in the data lake, so changing it would orphan history.
        city.setName(request.name().trim());
        city.setCountry(request.country().trim());
        city.setCountryCode(request.countryCode());
        city.setTimezone(request.timezone());
        city.setCenterLatitude(request.centerLatitude());
        city.setCenterLongitude(request.centerLongitude());
        if (request.defaultZoom() != null) {
            city.setDefaultZoom(request.defaultZoom());
        }
        city.setPopulation(request.population());
        city.setAreaSqKm(request.areaSqKm());
        city.setActive(request.active());

        City saved = cityRepository.save(city);
        audit(AuditAction.CITY_UPDATED, saved, "Updated city " + saved.getSlug());
        return mapper.toCity(saved, zoneRepository.countByCityIdAndDeletedAtIsNull(saved.getId()));
    }

    /**
     * Soft delete. Telemetry, forecasts, and audit entries reference the city, so
     * removing the row would break history; marking it deleted hides it from
     * selection while keeping every reference resolvable.
     */
    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.CITY_WRITE + "')")
    public void delete(UUID uid) {
        City city = requireCity(uid);
        city.setDeletedAt(Instant.now());
        city.setActive(false);
        cityRepository.save(city);
        audit(AuditAction.CITY_DELETED, city, "Soft-deleted city " + city.getSlug());
    }

    /** Package-internal: the zone service needs the entity, other modules do not. */
    City requireCity(UUID uid) {
        return cityRepository.findByUidAndDeletedAtIsNull(uid)
                .orElseThrow(() -> new Exceptions.NotFound("City", uid));
    }

    private void audit(AuditAction action, City city, String detail) {
        auditService.recordResourceChange(action, null,
                currentUser.find().map(p -> p.email()).orElse(null),
                "CITY", city.getUid().toString(), detail);
    }
}
