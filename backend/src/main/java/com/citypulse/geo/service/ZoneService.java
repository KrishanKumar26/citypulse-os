package com.citypulse.geo.service;

import com.citypulse.audit.domain.AuditAction;
import com.citypulse.audit.service.AuditService;
import com.citypulse.common.api.PageResponse;
import com.citypulse.common.exception.Exceptions;
import com.citypulse.geo.domain.City;
import com.citypulse.geo.domain.Zone;
import com.citypulse.geo.domain.ZoneType;
import com.citypulse.geo.dto.GeoMapper;
import com.citypulse.geo.dto.GeoRequests;
import com.citypulse.geo.dto.GeoResponses;
import com.citypulse.geo.repository.ZoneRepository;
import com.citypulse.security.CurrentUser;
import com.citypulse.user.domain.Permissions;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ZoneService {

    private final ZoneRepository zoneRepository;
    private final CityService cityService;
    private final GeoMapper mapper;
    private final AuditService auditService;
    private final CurrentUser currentUser;

    public ZoneService(ZoneRepository zoneRepository,
                       CityService cityService,
                       GeoMapper mapper,
                       AuditService auditService,
                       CurrentUser currentUser) {
        this.zoneRepository = zoneRepository;
        this.cityService = cityService;
        this.mapper = mapper;
        this.auditService = auditService;
        this.currentUser = currentUser;
    }

    /**
     * Unpaginated by design: the map renders all of a city's zones at once, and a
     * city has tens of zones, not thousands. {@link #search} covers the
     * administrative table view where paging is wanted.
     */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ZONE_READ + "')")
    public List<GeoResponses.ZoneResponse> listByCity(UUID cityUid, boolean activeOnly) {
        City city = cityService.requireCity(cityUid);
        return zoneRepository.findByCity(city.getId(), activeOnly).stream()
                .map(mapper::toZone)
                .toList();
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ZONE_READ + "')")
    public PageResponse<GeoResponses.ZoneResponse> search(UUID cityUid, String search, Pageable pageable) {
        City city = cityService.requireCity(cityUid);
        String normalised = (search == null || search.isBlank()) ? null : search.trim();
        return PageResponse.from(zoneRepository.search(city.getId(), normalised, pageable), mapper::toZone);
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ZONE_READ + "')")
    public GeoResponses.ZoneResponse get(UUID zoneUid) {
        return mapper.toZone(requireZone(zoneUid));
    }

    /** Boundary geometry is served on demand; see {@code ZoneBoundaryResponse}. */
    @Transactional(readOnly = true)
    @PreAuthorize("hasAuthority('" + Permissions.ZONE_READ + "')")
    public GeoResponses.ZoneBoundaryResponse getBoundary(UUID zoneUid) {
        return mapper.toBoundary(requireZone(zoneUid));
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.ZONE_WRITE + "')")
    public GeoResponses.ZoneResponse create(UUID cityUid, GeoRequests.CreateZone request) {
        City city = cityService.requireCity(cityUid);

        if (zoneRepository.existsByCityIdAndCodeAndDeletedAtIsNull(city.getId(), request.code())) {
            throw new Exceptions.Conflict(
                    "Zone code '%s' already exists in %s".formatted(request.code(), city.getName()));
        }

        Zone zone = new Zone();
        zone.setCity(city);
        zone.setCode(request.code());
        zone.setName(request.name().trim());
        zone.setZoneType(ZoneType.valueOf(request.zoneType()));
        zone.setCenterLatitude(request.centerLatitude());
        zone.setCenterLongitude(request.centerLongitude());
        zone.setAreaSqKm(request.areaSqKm());
        zone.setPopulation(request.population());
        zone.setRoadCapacityVph(request.roadCapacityVph());
        zone.setBoundaryGeoJson(request.boundaryGeoJson());

        Zone saved = zoneRepository.save(zone);
        audit(AuditAction.ZONE_CREATED, saved, "Created zone %s in %s".formatted(saved.getCode(), city.getSlug()));
        return mapper.toZone(saved);
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.ZONE_WRITE + "')")
    public GeoResponses.ZoneResponse update(UUID zoneUid, GeoRequests.UpdateZone request) {
        Zone zone = requireZone(zoneUid);

        // Code is immutable for the same reason a city slug is: it identifies the
        // zone in lake partitions and in every historical metric row.
        zone.setName(request.name().trim());
        zone.setZoneType(ZoneType.valueOf(request.zoneType()));
        zone.setCenterLatitude(request.centerLatitude());
        zone.setCenterLongitude(request.centerLongitude());
        zone.setAreaSqKm(request.areaSqKm());
        zone.setPopulation(request.population());
        zone.setRoadCapacityVph(request.roadCapacityVph());
        zone.setBoundaryGeoJson(request.boundaryGeoJson());
        zone.setActive(request.active());

        Zone saved = zoneRepository.save(zone);
        audit(AuditAction.ZONE_UPDATED, saved, "Updated zone " + saved.getCode());
        return mapper.toZone(saved);
    }

    @Transactional
    @PreAuthorize("hasAuthority('" + Permissions.ZONE_WRITE + "')")
    public void delete(UUID zoneUid) {
        Zone zone = requireZone(zoneUid);
        zone.setDeletedAt(Instant.now());
        zone.setActive(false);
        zoneRepository.save(zone);
        audit(AuditAction.ZONE_DELETED, zone, "Soft-deleted zone " + zone.getCode());
    }

    private Zone requireZone(UUID uid) {
        return zoneRepository.findByUidAndDeletedAtIsNull(uid)
                .orElseThrow(() -> new Exceptions.NotFound("Zone", uid));
    }

    private void audit(AuditAction action, Zone zone, String detail) {
        auditService.recordResourceChange(action, null,
                currentUser.find().map(p -> p.email()).orElse(null),
                "ZONE", zone.getUid().toString(), detail);
    }
}
