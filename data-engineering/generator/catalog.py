"""Reference data the generator produces against.

Cities, zones and sources are read from PostgreSQL rather than hardcoded. That
matters for more than tidiness: the generator emits `zone_code` values that the
pipeline validates against the same table, so if the two were maintained
separately every zone rename would turn into a silent flood of UNKNOWN_ZONE
rejections.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from typing import Any
from zoneinfo import ZoneInfo

import psycopg
from psycopg.rows import dict_row


@dataclass(slots=True, frozen=True)
class Zone:
    id: int
    code: str
    name: str
    zone_type: str
    latitude: float
    longitude: float
    population: int | None
    road_capacity_vph: int | None
    city_slug: str

    @property
    def capacity(self) -> int:
        """Rated vehicles per hour, with a floor.

        A zone with no recorded capacity would otherwise divide by zero when
        computing occupancy. 1,000 vph is a conservative stand-in for a minor
        urban road, and the resulting occupancy is still meaningful.
        """
        return self.road_capacity_vph or 1000


@dataclass(slots=True, frozen=True)
class City:
    id: int
    slug: str
    name: str
    timezone: str
    latitude: float
    longitude: float

    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.timezone)


@dataclass(slots=True, frozen=True)
class Source:
    id: int
    code: str
    source_type: str
    status: str
    config: dict[str, Any]


@dataclass(slots=True, frozen=True)
class Catalog:
    cities: tuple[City, ...]
    zones: tuple[Zone, ...]
    sources: tuple[Source, ...]

    def zones_for(self, city_slug: str) -> tuple[Zone, ...]:
        return tuple(z for z in self.zones if z.city_slug == city_slug)

    def source(self, code: str) -> Source:
        for source in self.sources:
            if source.code == code:
                return source
        raise KeyError(f"data source {code!r} is not seeded; check V5__seed_data_sources.sql")

    def active_source(self, code: str) -> Source | None:
        """The source, or None when an operator has paused it in the database."""
        source = self.source(code)
        return source if source.status == "ACTIVE" else None


def database_url() -> str:
    """Build a libpq URL from the same environment the backend uses.

    Reads CITYPULSE_DB_* so there is one set of credentials for the whole
    platform, and accepts the JDBC form the backend uses by stripping the
    `jdbc:` prefix rather than requiring a second variable to be kept in sync.
    """
    explicit = os.environ.get("CITYPULSE_PG_DSN")
    if explicit:
        return explicit

    url = os.environ.get("CITYPULSE_DB_URL", "jdbc:postgresql://localhost:5432/citypulse")
    url = url.removeprefix("jdbc:")
    user = os.environ.get("CITYPULSE_DB_USER", "citypulse")
    password = os.environ.get("CITYPULSE_DB_PASSWORD")
    if not password:
        raise RuntimeError(
            "CITYPULSE_DB_PASSWORD is not set. Source the repository .env before "
            "running the pipeline; credentials are never defaulted (PRD §30)."
        )

    scheme, _, rest = url.partition("://")
    return f"{scheme}://{user}:{password}@{rest}"


def connect() -> psycopg.Connection:
    return psycopg.connect(database_url(), row_factory=dict_row)


def load(connection: psycopg.Connection) -> Catalog:
    """Read active cities, zones and sources.

    Soft-deleted and inactive rows are excluded here rather than filtered by
    every caller, so a deactivated zone stops producing events immediately.
    """
    with connection.cursor() as cursor:
        cursor.execute(
            """
            SELECT id, slug, name, timezone, center_latitude, center_longitude
            FROM cities
            WHERE deleted_at IS NULL AND active
            ORDER BY slug
            """
        )
        cities = tuple(
            City(
                id=row["id"],
                slug=row["slug"],
                name=row["name"],
                timezone=row["timezone"],
                latitude=float(row["center_latitude"]),
                longitude=float(row["center_longitude"]),
            )
            for row in cursor.fetchall()
        )

        cursor.execute(
            """
            SELECT z.id, z.code, z.name, z.zone_type, z.center_latitude,
                   z.center_longitude, z.population, z.road_capacity_vph, c.slug AS city_slug
            FROM zones z
            JOIN cities c ON c.id = z.city_id
            WHERE z.deleted_at IS NULL AND z.active
              AND c.deleted_at IS NULL AND c.active
            ORDER BY c.slug, z.code
            """
        )
        zones = tuple(
            Zone(
                id=row["id"],
                code=row["code"],
                name=row["name"],
                zone_type=row["zone_type"],
                latitude=float(row["center_latitude"]),
                longitude=float(row["center_longitude"]),
                population=row["population"],
                road_capacity_vph=row["road_capacity_vph"],
                city_slug=row["city_slug"],
            )
            for row in cursor.fetchall()
        )

        cursor.execute(
            """
            SELECT id, code, source_type, status, config
            FROM data_sources
            WHERE deleted_at IS NULL
            ORDER BY code
            """
        )
        sources = tuple(
            Source(
                id=row["id"],
                code=row["code"],
                source_type=row["source_type"],
                status=row["status"],
                # psycopg returns JSONB already decoded; the isinstance guard
                # keeps this working if the column type ever changes to text.
                config=row["config"] if isinstance(row["config"], dict) else json.loads(row["config"]),
            )
            for row in cursor.fetchall()
        )

    return Catalog(cities=cities, zones=zones, sources=sources)
