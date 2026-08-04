"""The synthetic city model.

The important property here is that the signals are *coupled*. Rain suppresses
speed and scrubs particulates; congestion raises both AQI and the accident rate;
a stadium event lifts traffic in its zone hours before it starts. If these were
five independent random streams the data would look plausible on any single
chart and the correlation engine (PRD §12) would correctly find nothing — the
platform's central claim would have nothing to stand on.

Every random draw goes through `self._rng`, seeded per run. Given the same seed
and the same simulated clock, the world replays identically, which is what makes
the pipeline testable end to end.
"""

from __future__ import annotations

import random
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from typing import Any, Iterator

from common.events import (
    OCCUPANCY_PRECISION,
    AirQualityEvent,
    CityEventEvent,
    CityEventType,
    Envelope,
    EventType,
    IncidentEvent,
    IncidentType,
    Severity,
    TrafficEvent,
    WeatherCondition,
    WeatherEvent,
)
from common.transforms import congestion_level, speed_from_occupancy

from . import patterns
from .catalog import Catalog, City, Zone


# Rain slows traffic and raises crash risk. Multipliers on effective road
# capacity — wet roads carry fewer vehicles at a given speed.
_RAIN_CAPACITY_FACTOR: dict[WeatherCondition, float] = {
    WeatherCondition.CLEAR: 1.00,
    WeatherCondition.CLOUDY: 1.00,
    WeatherCondition.OVERCAST: 0.99,
    WeatherCondition.HAZE: 0.97,
    WeatherCondition.FOG: 0.82,
    WeatherCondition.LIGHT_RAIN: 0.93,
    WeatherCondition.RAIN: 0.85,
    WeatherCondition.HEAVY_RAIN: 0.72,
    WeatherCondition.THUNDERSTORM: 0.65,
}

_INCIDENT_TYPE_WEIGHTS: tuple[tuple[IncidentType, float], ...] = (
    (IncidentType.BREAKDOWN, 0.34),
    (IncidentType.ACCIDENT, 0.26),
    (IncidentType.CONSTRUCTION, 0.12),
    (IncidentType.SIGNAL_FAILURE, 0.10),
    (IncidentType.ROAD_CLOSURE, 0.07),
    (IncidentType.FLOODING, 0.05),
    (IncidentType.PROTEST, 0.03),
    (IncidentType.FIRE, 0.02),
    (IncidentType.OTHER, 0.01),
)

_EVENT_NAMES: dict[CityEventType, tuple[str, ...]] = {
    CityEventType.SPORTS: ("Premier League Match", "T20 Fixture", "Derby Night", "Marathon"),
    CityEventType.CONCERT: ("Arena Live", "Indie Night", "Symphony Evening", "Playback Night"),
    CityEventType.FESTIVAL: ("City Food Festival", "Lantern Festival", "Harvest Mela"),
    CityEventType.CONFERENCE: ("Tech Summit", "Startup Expo", "Trade Convention"),
    CityEventType.PARADE: ("Republic Day Parade", "Heritage Walk"),
    CityEventType.RELIGIOUS: ("Temple Procession", "Community Gathering"),
    CityEventType.MARKET: ("Weekend Bazaar", "Farmers Market"),
    CityEventType.POLITICAL: ("Public Rally",),
    CityEventType.OTHER: ("Civic Gathering",),
}


@dataclass(slots=True)
class WeatherState:
    """Current conditions for one city, carried forward between ticks.

    Weather is autocorrelated — it drifts rather than being redrawn each tick.
    Independent draws would produce a city that flips between clear and
    thunderstorm every minute, which would break every downstream correlation.
    """

    condition: WeatherCondition
    precipitation_mm_h: float
    temperature_c: float
    humidity_pct: float
    wind_speed_kph: float
    # Ticks left before the current condition may change.
    persistence: int = 0


@dataclass(slots=True)
class ActiveIncident:
    zone_code: str
    external_id: str
    incident_type: IncidentType
    severity: Severity
    started_at: datetime
    ends_at: datetime
    latitude: float
    longitude: float
    lanes_blocked: int
    emitted_open: bool = False


@dataclass(slots=True)
class ScheduledEvent:
    zone_code: str
    external_id: str
    category: CityEventType
    name: str
    venue: str
    attendance: int
    starts_at: datetime
    ends_at: datetime
    announced: bool = False


@dataclass(slots=True)
class CityState:
    city: City
    weather: WeatherState
    incidents: dict[str, ActiveIncident] = field(default_factory=dict)
    events: list[ScheduledEvent] = field(default_factory=list)


class World:
    """Couples the five feeds into one evolving city."""

    def __init__(
        self,
        catalog: Catalog,
        *,
        seed: int | None = None,
        traffic_config: dict[str, Any] | None = None,
        weather_config: dict[str, Any] | None = None,
        air_quality_config: dict[str, Any] | None = None,
        incident_config: dict[str, Any] | None = None,
        city_event_config: dict[str, Any] | None = None,
    ) -> None:
        self._catalog = catalog
        self._rng = random.Random(seed)
        self._traffic_cfg = traffic_config or {}
        self._weather_cfg = weather_config or {}
        self._aqi_cfg = air_quality_config or {}
        self._incident_cfg = incident_config or {}
        self._event_cfg = city_event_config or {}

        self._states: dict[str, CityState] = {
            city.slug: CityState(city=city, weather=self._initial_weather(city))
            for city in catalog.cities
        }

    # --- weather --------------------------------------------------------------

    def _initial_weather(self, city: City) -> WeatherState:
        return WeatherState(
            condition=WeatherCondition.CLEAR,
            precipitation_mm_h=0.0,
            temperature_c=float(self._weather_cfg.get("base_temperature_c", 27.0)),
            humidity_pct=60.0,
            wind_speed_kph=8.0,
        )

    def _advance_weather(self, state: CityState, moment: datetime) -> WeatherState:
        weather = state.weather
        local = moment.astimezone(state.city.tz)

        target_temp = patterns.temperature_c(
            local,
            base_temperature_c=float(self._weather_cfg.get("base_temperature_c", 27.0)),
            diurnal_swing_c=float(self._weather_cfg.get("diurnal_swing_c", 7.0)),
        )
        # Ease toward the curve instead of snapping to it, so consecutive
        # readings differ by a plausible amount.
        weather.temperature_c += (target_temp - weather.temperature_c) * 0.25
        weather.temperature_c += self._rng.gauss(0.0, 0.15)

        if weather.persistence > 0:
            weather.persistence -= 1
        else:
            season = patterns.monsoon_intensity(local)
            rain_p = float(self._weather_cfg.get("rain_probability", 0.18)) * season
            heavy_p = float(self._weather_cfg.get("heavy_rain_probability", 0.05)) * season
            roll = self._rng.random()

            if roll < heavy_p:
                weather.condition = self._rng.choice(
                    (WeatherCondition.HEAVY_RAIN, WeatherCondition.THUNDERSTORM)
                )
                weather.precipitation_mm_h = self._rng.uniform(18.0, 55.0)
                weather.persistence = self._rng.randint(3, 10)
            elif roll < rain_p:
                weather.condition = self._rng.choice(
                    (WeatherCondition.LIGHT_RAIN, WeatherCondition.RAIN)
                )
                weather.precipitation_mm_h = self._rng.uniform(0.5, 12.0)
                weather.persistence = self._rng.randint(5, 20)
            else:
                # Fog is a cool, still, early-morning phenomenon, not a random one.
                if local.hour in (5, 6, 7) and weather.temperature_c < 18.0 and self._rng.random() < 0.25:
                    weather.condition = WeatherCondition.FOG
                elif self._rng.random() < 0.30:
                    weather.condition = WeatherCondition.CLOUDY
                else:
                    weather.condition = WeatherCondition.CLEAR
                weather.precipitation_mm_h = 0.0
                weather.persistence = self._rng.randint(5, 25)

        raining = weather.precipitation_mm_h > 0
        target_humidity = 88.0 if raining else 55.0
        weather.humidity_pct += (target_humidity - weather.humidity_pct) * 0.3
        weather.humidity_pct = max(5.0, min(100.0, weather.humidity_pct + self._rng.gauss(0, 1.0)))

        target_wind = 22.0 if weather.condition == WeatherCondition.THUNDERSTORM else 9.0
        weather.wind_speed_kph += (target_wind - weather.wind_speed_kph) * 0.25
        weather.wind_speed_kph = max(0.0, weather.wind_speed_kph + self._rng.gauss(0, 0.8))

        return weather

    # --- traffic --------------------------------------------------------------

    def _event_traffic_boost(self, state: CityState, zone_code: str, moment: datetime) -> float:
        """Extra demand from a scheduled event.

        Arrivals build over the two hours before the start and departures spike
        in the hour after the end, which is why the boost is not simply "on
        during the event".
        """
        boost = 1.0
        for event in state.events:
            if event.zone_code != zone_code:
                continue
            scale = min(1.0, event.attendance / 40_000.0)
            if event.starts_at - timedelta(hours=2) <= moment < event.starts_at:
                progress = 1.0 - (event.starts_at - moment) / timedelta(hours=2)
                boost += 0.85 * scale * progress
            elif event.starts_at <= moment <= event.ends_at:
                boost += 0.25 * scale
            elif event.ends_at < moment <= event.ends_at + timedelta(hours=1):
                progress = 1.0 - (moment - event.ends_at) / timedelta(hours=1)
                boost += 1.10 * scale * progress
        return boost

    # Floor on how much capacity incidents can remove, combined.
    #
    # The per-incident floor alone is not enough: the factors multiply, so three
    # incidents at 0.35 each leave 4% of capacity — a road losing 96% of its
    # throughput, which is not a thing that happens. Occupancy then exceeded 26x
    # capacity and the pipeline correctly rejected it as out of range.
    #
    # Even a badly blocked arterial keeps moving something, so the *product* is
    # floored rather than each term.
    _MIN_INCIDENT_CAPACITY = 0.30

    def _incident_capacity_factor(self, state: CityState, zone_code: str) -> float:
        """Lanes blocked by open incidents reduce usable capacity."""
        factor = 1.0
        for incident in state.incidents.values():
            if incident.zone_code == zone_code:
                factor *= max(0.35, 1.0 - 0.18 * incident.lanes_blocked)
        return max(self._MIN_INCIDENT_CAPACITY, factor)

    def _traffic_for(self, state: CityState, zone: Zone, moment: datetime) -> TrafficEvent:
        local = moment.astimezone(state.city.tz)

        demand = patterns.traffic_demand_multiplier(
            local,
            zone_type=zone.zone_type,
            morning_peak_hour=float(self._traffic_cfg.get("morning_peak_hour", 9)),
            evening_peak_hour=float(self._traffic_cfg.get("evening_peak_hour", 18)),
            peak_multiplier=float(self._traffic_cfg.get("peak_multiplier", 2.4)),
            night_multiplier=float(self._traffic_cfg.get("night_multiplier", 0.18)),
            weekend_multiplier=float(self._traffic_cfg.get("weekend_multiplier", 0.65)),
        )
        demand *= self._event_traffic_boost(state, zone.code, moment)
        demand *= self._rng.gauss(1.0, 0.06)

        # Baseline flow is a fraction of rated capacity; demand moves around it.
        vehicles_per_hour = zone.capacity * 0.42 * demand

        effective_capacity = (
            zone.capacity
            * _RAIN_CAPACITY_FACTOR.get(state.weather.condition, 1.0)
            * self._incident_capacity_factor(state, zone.code)
        )
        # Rounded to the stored precision *before* the label is derived, so the
        # event that goes on the wire is internally consistent. Labelling the
        # full-precision value and rounding afterwards puts every band boundary
        # one rounding step away from contradicting itself.
        occupancy = round(
            max(0.0, vehicles_per_hour / max(1.0, effective_capacity)),
            OCCUPANCY_PRECISION,
        )

        speed = speed_from_occupancy(
            occupancy,
            free_flow_kph=float(self._traffic_cfg.get("free_flow_speed_kph", 48.0)),
            jam_kph=float(self._traffic_cfg.get("jam_speed_kph", 8.0)),
        )
        speed = max(1.0, speed + self._rng.gauss(0.0, 1.2))

        return TrafficEvent(
            envelope=Envelope(
                event_id=str(uuid.UUID(int=self._rng.getrandbits(128), version=4)),
                event_type=EventType.TRAFFIC,
                source_code="synthetic-traffic",
                event_time=moment,
            ),
            zone_code=zone.code,
            vehicle_count=max(0, int(vehicles_per_hour / 60.0 * 10.0)),  # per 10-minute equivalent
            average_speed_kph=speed,
            occupancy_ratio=occupancy,
            congestion_level=congestion_level(occupancy),
        )

    # --- air quality ----------------------------------------------------------

    def _air_quality_for(
        self, state: CityState, zone: Zone, moment: datetime, traffic_multiplier: float
    ) -> AirQualityEvent:
        local = moment.astimezone(state.city.tz)

        value = patterns.aqi_baseline(
            local,
            base_aqi=float(self._aqi_cfg.get("base_aqi", 95)),
            traffic_multiplier=traffic_multiplier,
            traffic_coupling=float(self._aqi_cfg.get("traffic_coupling", 0.55)),
            industrial_penalty=float(self._aqi_cfg.get("industrial_zone_penalty", 35)),
            is_industrial=zone.zone_type == "INDUSTRIAL",
        )

        # Rain washes particulates out of the air — the clearest cross-signal
        # relationship in the dataset, and one the correlation engine should find.
        if state.weather.precipitation_mm_h > 0:
            washout = float(self._aqi_cfg.get("rain_washout_factor", 0.65))
            intensity = min(1.0, state.weather.precipitation_mm_h / 20.0)
            value *= 1.0 - (1.0 - washout) * intensity

        value = max(5.0, value * self._rng.gauss(1.0, 0.07))
        aqi = int(min(1000, round(value)))

        # PM2.5 is the dominant AQI driver in these cities; the others are
        # derived around it in roughly the ratios monitoring stations report.
        pm25 = value * 0.55 * self._rng.uniform(0.9, 1.1)
        return AirQualityEvent(
            envelope=Envelope(
                event_id=str(uuid.UUID(int=self._rng.getrandbits(128), version=4)),
                event_type=EventType.AIR_QUALITY,
                source_code="synthetic-air-quality",
                event_time=moment,
            ),
            zone_code=zone.code,
            aqi=aqi,
            pm25=pm25,
            pm10=pm25 * self._rng.uniform(1.5, 2.1),
            no2=value * 0.22 * self._rng.uniform(0.8, 1.2),
            o3=value * 0.18 * self._rng.uniform(0.7, 1.3),
            co=value * 0.012 * self._rng.uniform(0.8, 1.2),
        )

    # --- incidents ------------------------------------------------------------

    def _maybe_open_incident(
        self, state: CityState, zone: Zone, moment: datetime, occupancy: float, tick_seconds: float
    ) -> IncidentEvent | None:
        base_hourly = float(self._incident_cfg.get("base_hourly_rate_per_zone", 0.12))
        rate = base_hourly
        rate *= 1.0 + float(self._incident_cfg.get("congestion_multiplier", 3.0)) * max(0.0, occupancy - 0.5)
        if state.weather.precipitation_mm_h > 0:
            rate *= float(self._incident_cfg.get("rain_multiplier", 2.2))

        probability = rate * (tick_seconds / 3600.0)
        if self._rng.random() >= probability:
            return None

        incident_type = self._weighted_choice(_INCIDENT_TYPE_WEIGHTS)
        # Flooding only makes sense while it is actually raining.
        if incident_type == IncidentType.FLOODING and state.weather.precipitation_mm_h < 5.0:
            incident_type = IncidentType.BREAKDOWN

        severity = self._rng.choices(
            (Severity.LOW, Severity.MEDIUM, Severity.HIGH, Severity.CRITICAL),
            weights=(0.45, 0.35, 0.16, 0.04),
        )[0]
        mean_minutes = float(self._incident_cfg.get("mean_duration_minutes", 42))
        duration = timedelta(minutes=max(4.0, self._rng.expovariate(1.0 / mean_minutes)))
        lanes = {Severity.LOW: 0, Severity.MEDIUM: 1, Severity.HIGH: 2, Severity.CRITICAL: 3}[severity]

        external_id = f"INC-{zone.code}-{int(moment.timestamp())}"
        incident = ActiveIncident(
            zone_code=zone.code,
            external_id=external_id,
            incident_type=incident_type,
            severity=severity,
            started_at=moment,
            ends_at=moment + duration,
            latitude=zone.latitude + self._rng.gauss(0, 0.004),
            longitude=zone.longitude + self._rng.gauss(0, 0.004),
            lanes_blocked=lanes,
        )
        state.incidents[external_id] = incident

        return self._incident_event(incident, moment, status="REPORTED", resolved_at=None)

    def _incident_event(
        self, incident: ActiveIncident, moment: datetime, *, status: str, resolved_at: datetime | None
    ) -> IncidentEvent:
        return IncidentEvent(
            envelope=Envelope(
                event_id=str(uuid.UUID(int=self._rng.getrandbits(128), version=4)),
                event_type=EventType.INCIDENT,
                source_code="synthetic-incidents",
                event_time=moment,
            ),
            zone_code=incident.zone_code,
            external_id=incident.external_id,
            incident_type=incident.incident_type,
            severity=incident.severity,
            status=status,
            description=f"{incident.incident_type.value.replace('_', ' ').title()} reported in {incident.zone_code}",
            latitude=incident.latitude,
            longitude=incident.longitude,
            lanes_blocked=incident.lanes_blocked,
            started_at=incident.started_at,
            resolved_at=resolved_at,
        )

    def _close_due_incidents(self, state: CityState, moment: datetime) -> list[IncidentEvent]:
        closing = [i for i in state.incidents.values() if i.ends_at <= moment]
        events = []
        for incident in closing:
            del state.incidents[incident.external_id]
            events.append(
                self._incident_event(incident, moment, status="CLEARED", resolved_at=moment)
            )
        return events

    # --- city events ----------------------------------------------------------

    def _schedule_events(self, state: CityState, moment: datetime) -> list[CityEventEvent]:
        """Keep roughly a week of scheduled events ahead of the clock."""
        per_week = float(self._event_cfg.get("events_per_week_per_city", 4))
        horizon = moment + timedelta(days=7)

        state.events = [e for e in state.events if e.ends_at > moment - timedelta(hours=2)]
        upcoming = [e for e in state.events if e.starts_at > moment]

        emitted: list[CityEventEvent] = []
        while len(upcoming) < per_week:
            zones = self._catalog.zones_for(state.city.slug)
            if not zones:
                break
            # Events land where crowds gather, not uniformly across the city.
            venue_zone = self._rng.choices(
                zones,
                weights=[
                    3.0 if z.zone_type in ("RECREATIONAL", "COMMERCIAL", "TRANSIT_HUB") else 1.0
                    for z in zones
                ],
            )[0]

            category = self._rng.choice(list(CityEventType))
            start_day = moment + timedelta(days=self._rng.uniform(0.2, 7.0))
            local_day = start_day.astimezone(state.city.tz)
            hour = 19 if self._rng.random() < float(self._event_cfg.get("evening_start_bias", 0.7)) else 11
            starts_local = local_day.replace(hour=hour, minute=0, second=0, microsecond=0)
            starts = starts_local.astimezone(timezone.utc)
            if starts > horizon:
                starts = horizon
            duration = timedelta(hours=self._rng.uniform(2.0, 5.0))

            scheduled = ScheduledEvent(
                zone_code=venue_zone.code,
                external_id=f"EVT-{venue_zone.code}-{int(starts.timestamp())}",
                category=category,
                name=self._rng.choice(_EVENT_NAMES[category]),
                venue=f"{venue_zone.name} Grounds",
                attendance=self._rng.randint(
                    int(self._event_cfg.get("min_attendance", 2000)),
                    int(self._event_cfg.get("max_attendance", 60000)),
                ),
                starts_at=starts,
                ends_at=starts + duration,
            )
            state.events.append(scheduled)
            upcoming.append(scheduled)

            emitted.append(
                CityEventEvent(
                    envelope=Envelope(
                        event_id=str(uuid.UUID(int=self._rng.getrandbits(128), version=4)),
                        event_type=EventType.CITY_EVENT,
                        source_code="synthetic-city-events",
                        event_time=moment,
                    ),
                    zone_code=scheduled.zone_code,
                    external_id=scheduled.external_id,
                    event_category=scheduled.category,
                    name=scheduled.name,
                    venue=scheduled.venue,
                    expected_attendance=scheduled.attendance,
                    starts_at=scheduled.starts_at,
                    ends_at=scheduled.ends_at,
                    status="SCHEDULED",
                )
            )
        return emitted

    # --- tick -----------------------------------------------------------------

    def _weighted_choice(self, options: tuple[tuple[Any, float], ...]) -> Any:
        values = [value for value, _ in options]
        weights = [weight for _, weight in options]
        return self._rng.choices(values, weights=weights)[0]

    def tick(self, moment: datetime, *, tick_seconds: float, emit_slow_feeds: bool) -> Iterator[Any]:
        """Produce every event for one instant.

        `emit_slow_feeds` gates weather, air quality and city events, which are
        configured to emit far less often than traffic. Emitting all five at the
        traffic rate would flood the topics with readings that do not change
        meaningfully between ticks.
        """
        for state in self._states.values():
            weather = self._advance_weather(state, moment)

            if emit_slow_feeds:
                yield WeatherEvent(
                    envelope=Envelope(
                        event_id=str(uuid.UUID(int=self._rng.getrandbits(128), version=4)),
                        event_type=EventType.WEATHER,
                        source_code="synthetic-weather",
                        event_time=moment,
                    ),
                    city_slug=state.city.slug,
                    temperature_c=weather.temperature_c,
                    humidity_pct=weather.humidity_pct,
                    precipitation_mm_h=weather.precipitation_mm_h,
                    wind_speed_kph=weather.wind_speed_kph,
                    visibility_km=2.0 if weather.condition == WeatherCondition.FOG else 10.0,
                    condition=weather.condition,
                )
                yield from self._schedule_events(state, moment)

            yield from self._close_due_incidents(state, moment)

            for zone in self._catalog.zones_for(state.city.slug):
                traffic = self._traffic_for(state, zone, moment)
                yield traffic

                if emit_slow_feeds:
                    local = moment.astimezone(state.city.tz)
                    demand = patterns.traffic_demand_multiplier(local, zone_type=zone.zone_type)
                    yield self._air_quality_for(state, zone, moment, demand)

                opened = self._maybe_open_incident(
                    state, zone, moment, float(traffic.occupancy_ratio), tick_seconds
                )
                if opened is not None:
                    yield opened
