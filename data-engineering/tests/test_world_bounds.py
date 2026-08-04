"""The generator must not produce readings the pipeline will reject.

Regression cover for a defect four weeks of history surfaced: incident capacity
factors multiply, so three concurrent incidents in one zone left 4% of rated
capacity (0.35³). Occupancy then reached 26x — a road losing 96% of its
throughput, which does not happen — and the validator correctly quarantined the
records as VALUE_OUT_OF_RANGE.

The DLQ catching it was the system working. But a generator that routinely
emits invalid data makes the rejection rate meaningless as a health signal: real
feed problems would hide among the self-inflicted ones.
"""

from __future__ import annotations

import json
from datetime import datetime, timedelta, timezone

import pytest

from common.validation import ReferenceData, Valid, validate
from generator.catalog import Catalog, City, Source, Zone
from generator.world import ActiveIncident, World
from common.events import IncidentType, Severity


def _catalog() -> Catalog:
    city = City(id=1, slug="testville", name="Testville", timezone="Asia/Kolkata",
                latitude=12.97, longitude=77.59)
    zones = tuple(
        Zone(id=i + 1, code=f"TV-{i:02d}", name=f"Zone {i}", zone_type=zone_type,
             latitude=12.97 + i * 0.01, longitude=77.59 + i * 0.01,
             population=100_000, road_capacity_vph=6000, city_slug="testville")
        for i, zone_type in enumerate(
            ["COMMERCIAL", "TRANSIT_HUB", "INDUSTRIAL", "RESIDENTIAL"])
    )
    sources = tuple(
        Source(id=i + 1, code=code, source_type=t, status="ACTIVE", config={})
        for i, (code, t) in enumerate([
            ("synthetic-traffic", "TRAFFIC"),
            ("synthetic-weather", "WEATHER"),
            ("synthetic-air-quality", "AIR_QUALITY"),
            ("synthetic-incidents", "INCIDENT"),
            ("synthetic-city-events", "CITY_EVENT"),
        ]))
    return Catalog(cities=(city,), zones=zones, sources=sources)


def _reference(catalog: Catalog) -> ReferenceData:
    return ReferenceData(
        zone_codes=frozenset(z.code for z in catalog.zones),
        city_slugs=frozenset(c.slug for c in catalog.cities),
        source_codes=frozenset(s.code for s in catalog.sources),
    )


class TestCapacityFloor:
    def test_stacked_incidents_cannot_erase_a_zone(self) -> None:
        """Even a badly blocked arterial keeps moving something."""
        catalog = _catalog()
        world = World(catalog, seed=1)
        state = world._states["testville"]

        # Four simultaneous critical incidents, each blocking three lanes.
        now = datetime(2026, 8, 4, 9, 0, tzinfo=timezone.utc)
        for i in range(4):
            state.incidents[f"INC-{i}"] = ActiveIncident(
                zone_code="TV-00", external_id=f"INC-{i}",
                incident_type=IncidentType.ACCIDENT, severity=Severity.CRITICAL,
                started_at=now, ends_at=now + timedelta(hours=2),
                latitude=12.97, longitude=77.59, lanes_blocked=3)

        factor = world._incident_capacity_factor(state, "TV-00")

        # Unfloored this is 0.35^4 ≈ 0.015, which produced occupancy above 26.
        assert factor >= World._MIN_INCIDENT_CAPACITY
        assert factor <= 1.0

    def test_no_incidents_leaves_capacity_untouched(self) -> None:
        world = World(_catalog(), seed=1)
        assert world._incident_capacity_factor(world._states["testville"], "TV-00") == 1.0


class TestGeneratedEventsAreValid:
    """Whatever the world produces, the pipeline must accept it."""

    @pytest.mark.parametrize("seed", [1, 7, 42, 101, 2026])
    def test_a_simulated_week_produces_no_rejections(self, seed: int) -> None:
        catalog = _catalog()
        reference = _reference(catalog)
        world = World(catalog, seed=seed)

        moment = datetime(2026, 7, 7, 0, 0, tzinfo=timezone.utc)
        end = moment + timedelta(days=7)
        rejections: list[str] = []
        emitted = 0

        tick = 0
        while moment < end:
            for event in world.tick(moment, tick_seconds=300, emit_slow_feeds=tick % 6 == 0):
                emitted += 1
                raw = json.dumps(event.to_dict())
                # `now` follows the simulated clock so lateness is not the thing
                # under test here; the point is value ranges and enum validity.
                outcome = validate(raw, reference, now=moment,
                                   max_lateness=timedelta(days=30))
                if not isinstance(outcome, Valid):
                    rejections.append(f"{outcome.reason_code}: {outcome.detail}")
            moment += timedelta(seconds=300)
            tick += 1

        assert emitted > 1000, "the simulation produced too little to be meaningful"
        assert rejections == [], (
            f"{len(rejections)} of {emitted} generated events would be rejected; "
            f"first few: {rejections[:3]}"
        )

    def test_occupancy_stays_inside_the_schema_bound(self) -> None:
        """The column is NUMERIC(6,4) with a CHECK of [0, 10]."""
        catalog = _catalog()
        world = World(catalog, seed=13)

        moment = datetime(2026, 7, 20, 0, 0, tzinfo=timezone.utc)
        peak_occupancy = 0.0
        for _ in range(2016):  # a week of five-minute ticks
            for event in world.tick(moment, tick_seconds=300, emit_slow_feeds=True):
                payload = event.to_dict()
                if payload["event_type"] == "TRAFFIC":
                    peak_occupancy = max(peak_occupancy, payload["occupancy_ratio"])
            moment += timedelta(seconds=300)

        assert peak_occupancy <= 10.0, f"peak occupancy {peak_occupancy} exceeds the schema bound"
        # And it should still reach genuinely congested territory, or the floor
        # has been set so high that the generator no longer models gridlock.
        assert peak_occupancy > 1.0, "the simulation never produced above-capacity traffic"
