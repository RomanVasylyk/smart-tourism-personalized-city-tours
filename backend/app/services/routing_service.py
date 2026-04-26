import os
from dataclasses import dataclass
from math import asin, cos, radians, sin, sqrt

import httpx


@dataclass(frozen=True)
class RoutePoint:
    lat: float
    lon: float


@dataclass(frozen=True)
class RoutingLeg:
    duration_seconds: float
    distance_meters: float
    geometry: list[dict]
    source: str

    @property
    def duration_minutes(self) -> int:
        return max(1, round(self.duration_seconds / 60))


class RoutingService:
    def __init__(
        self,
        base_url: str | None = None,
        profile: str | None = None,
        timeout_seconds: float | None = None,
        enabled: bool | None = None,
    ):
        self.base_url = (base_url or os.getenv("ROUTING_BASE_URL", "https://router.project-osrm.org")).rstrip("/")
        self.profile = profile or os.getenv("ROUTING_PROFILE", "foot")
        self.timeout_seconds = timeout_seconds or float(os.getenv("ROUTING_TIMEOUT_SECONDS", "0.75"))
        self.enabled = enabled if enabled is not None else os.getenv("ROUTING_ENABLED", "true").lower() == "true"
        self._cache: dict[tuple[float, float, float, float, str, str, bool], RoutingLeg] = {}
        self._external_routing_failed = False

    def route_between(self, start: RoutePoint, end: RoutePoint, pace: str) -> RoutingLeg:
        return self.route_between_profile(
            start=start,
            end=end,
            pace=pace,
            profile=self.profile,
            apply_pace_multiplier=True,
        )

    def route_between_profile(
        self,
        start: RoutePoint,
        end: RoutePoint,
        pace: str,
        profile: str,
        apply_pace_multiplier: bool,
    ) -> RoutingLeg:
        cache_key = (
            round(start.lat, 6),
            round(start.lon, 6),
            round(end.lat, 6),
            round(end.lon, 6),
            (pace or "normal").lower(),
            (profile or self.profile or "foot").lower(),
            bool(apply_pace_multiplier),
        )
        cached_leg = self._cache.get(cache_key)
        if cached_leg is not None:
            return cached_leg

        leg = None
        if not self._external_routing_failed:
            leg = self._fetch_osrm_route(
                start=start,
                end=end,
                pace=pace,
                profile=profile,
                apply_pace_multiplier=apply_pace_multiplier,
            )
        if leg is None:
            leg = self._fallback_route(start, end, pace)

        self._cache[cache_key] = leg
        return leg

    def route_geometry_between(self, start: RoutePoint, end: RoutePoint, profile: str = "driving") -> RoutingLeg:
        return self.route_between_profile(
            start=start,
            end=end,
            pace="normal",
            profile=profile,
            apply_pace_multiplier=False,
        )

    def _fetch_osrm_route(
        self,
        start: RoutePoint,
        end: RoutePoint,
        pace: str,
        profile: str,
        apply_pace_multiplier: bool,
    ) -> RoutingLeg | None:
        if not self.enabled or not self.base_url:
            return None

        coordinates = f"{start.lon},{start.lat};{end.lon},{end.lat}"
        url = f"{self.base_url}/route/v1/{profile}/{coordinates}"
        params = {
            "overview": "full",
            "geometries": "geojson",
            "steps": "false",
        }

        try:
            response = httpx.get(url, params=params, timeout=self.timeout_seconds)
            response.raise_for_status()
            payload = response.json()
            route = (payload.get("routes") or [None])[0]
            if not route:
                self._external_routing_failed = True
                return None

            geometry = route.get("geometry") or {}
            raw_coordinates = geometry.get("coordinates") or []
            route_coordinates = [
                {"lat": float(lat), "lon": float(lon)}
                for lon, lat in raw_coordinates
                if lat is not None and lon is not None
            ]
            if len(route_coordinates) < 2:
                self._external_routing_failed = True
                return None

            duration_seconds = float(route.get("duration") or 0)
            distance_meters = float(route.get("distance") or 0)
            if duration_seconds <= 0 or distance_meters <= 0:
                self._external_routing_failed = True
                return None

            return RoutingLeg(
                duration_seconds=duration_seconds * pace_duration_multiplier(pace) if apply_pace_multiplier else duration_seconds,
                distance_meters=distance_meters,
                geometry=route_coordinates,
                source="osrm",
            )
        except (httpx.HTTPError, ValueError, TypeError, KeyError):
            self._external_routing_failed = True
            return None

    def _fallback_route(self, start: RoutePoint, end: RoutePoint, pace: str) -> RoutingLeg:
        distance_km = haversine_km(start.lat, start.lon, end.lat, end.lon)
        speed = walking_speed_kmh(pace)
        duration_seconds = (distance_km / speed) * 60 * 60

        return RoutingLeg(
            duration_seconds=max(60, duration_seconds),
            distance_meters=distance_km * 1000,
            geometry=[
                {"lat": start.lat, "lon": start.lon},
                {"lat": end.lat, "lon": end.lon},
            ],
            source="haversine_fallback",
        )


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    a = (
        sin(dlat / 2) ** 2
        + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
    )
    c = 2 * asin(sqrt(a))
    return r * c


def walking_speed_kmh(pace: str) -> float:
    pace = (pace or "normal").lower()
    if pace == "slow":
        return 4.0
    if pace == "fast":
        return 5.6
    return 4.8


def pace_duration_multiplier(pace: str) -> float:
    return walking_speed_kmh("normal") / walking_speed_kmh(pace)


def get_routing_service() -> RoutingService:
    return RoutingService()
