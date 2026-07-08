import time
from dataclasses import dataclass
from math import asin, cos, radians, sin, sqrt

import httpx
from app.core.config import get_settings


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


class RoutingUnavailableError(Exception):
    """The OSRM backend is unreachable or erroring (a transient/infra failure).

    Distinct from a valid OSRM response that simply has no route for a specific
    pair of points, which is a per-pair data issue and must not disable routing
    for the rest of the request.
    """


class RoutingService:
    _shared_duration_cache: "dict[tuple[float, float, float, float, str], float]" = {}
    _SHARED_DURATION_CACHE_CAP = 50_000

    _circuit_consecutive_failures = 0
    _circuit_open_until = 0.0

    OSRM_MAX_ATTEMPTS = 3
    OSRM_RETRY_BACKOFF_SECONDS = 0.1
    CIRCUIT_FAILURE_THRESHOLD = 3
    CIRCUIT_RESET_SECONDS = 30.0

    def __init__(
        self,
        base_url: str | None = None,
        profile: str | None = None,
        timeout_seconds: float | None = None,
        enabled: bool | None = None,
        table_enabled: bool | None = None,
    ):
        settings = get_settings()
        self.base_url = (base_url or settings.routing_base_url).rstrip("/")
        self.profile = profile or settings.routing_profile
        self.timeout_seconds = timeout_seconds or settings.routing_timeout_seconds
        self.enabled = enabled if enabled is not None else settings.routing_enabled
        self.table_enabled = table_enabled if table_enabled is not None else settings.routing_table_enabled
        self._cache: dict[tuple[float, float, float, float, str, str, bool], RoutingLeg] = {}

    @classmethod
    def _circuit_closed(cls) -> bool:
        return time.monotonic() >= cls._circuit_open_until

    @classmethod
    def _record_osrm_success(cls) -> None:
        cls._circuit_consecutive_failures = 0
        cls._circuit_open_until = 0.0

    @classmethod
    def _record_osrm_failure(cls) -> None:
        cls._circuit_consecutive_failures += 1
        if cls._circuit_consecutive_failures >= cls.CIRCUIT_FAILURE_THRESHOLD:
            cls._circuit_open_until = time.monotonic() + cls.CIRCUIT_RESET_SECONDS

    @classmethod
    def reset_circuit(cls) -> None:
        cls._circuit_consecutive_failures = 0
        cls._circuit_open_until = 0.0

    def _osrm_attemptable(self) -> bool:
        return self.enabled and bool(self.base_url) and self._circuit_closed()

    def _request_osrm_json(self, url: str, params: dict) -> dict:

        last_error: Exception | None = None
        for attempt in range(self.OSRM_MAX_ATTEMPTS):
            try:
                response = httpx.get(url, params=params, timeout=self.timeout_seconds)
                response.raise_for_status()
                return response.json()
            except (httpx.HTTPError, ValueError) as exc:
                last_error = exc
                if attempt + 1 < self.OSRM_MAX_ATTEMPTS:
                    time.sleep(self.OSRM_RETRY_BACKOFF_SECONDS * (2**attempt))
        raise RoutingUnavailableError(str(last_error) if last_error else "OSRM request failed")

    # --- /table matrix ---------------------------------------------------

    def duration_matrix(
        self,
        sources: list[RoutePoint],
        destinations: list[RoutePoint],
        pace: str,
        profile: str | None = None,
    ) -> list[list[float | None]] | None:

        if not self.enabled or not self.base_url or not self.table_enabled or not self._circuit_closed():
            return None
        if not sources or not destinations:
            return [[0.0 for _ in destinations] for _ in sources]

        resolved_profile = (profile or self.profile or "foot").lower()
        pace_multiplier = pace_duration_multiplier(pace)

        raw_matrix: list[list[float | None]] = [[None] * len(destinations) for _ in sources]
        has_missing = False
        for source_index, source in enumerate(sources):
            for destination_index, destination in enumerate(destinations):
                cached = self._shared_duration_cache.get(_duration_cell_key(source, destination, resolved_profile))
                if cached is None:
                    has_missing = True
                else:
                    raw_matrix[source_index][destination_index] = cached

        if has_missing:
            try:
                fetched = self._fetch_osrm_table(sources, destinations, resolved_profile)
                self._record_osrm_success()
            except RoutingUnavailableError:
                self._record_osrm_failure()
                return None

            for source_index, source in enumerate(sources):
                for destination_index, destination in enumerate(destinations):
                    if raw_matrix[source_index][destination_index] is not None:
                        continue
                    value = fetched[source_index][destination_index]
                    if value is None:
                        continue
                    raw_matrix[source_index][destination_index] = value
                    self._store_duration_cell(source, destinations[destination_index], resolved_profile, value)

        return [[None if raw is None else raw * pace_multiplier for raw in row] for row in raw_matrix]

    def _fetch_osrm_table(
        self,
        sources: list[RoutePoint],
        destinations: list[RoutePoint],
        profile: str,
    ) -> list[list[float | None]]:
        points = [*sources, *destinations]
        coordinates = ";".join(f"{point.lon},{point.lat}" for point in points)
        source_indexes = ";".join(str(index) for index in range(len(sources)))
        destination_indexes = ";".join(str(len(sources) + index) for index in range(len(destinations)))
        url = f"{self.base_url}/table/v1/{profile}/{coordinates}"
        params = {
            "sources": source_indexes,
            "destinations": destination_indexes,
            "annotations": "duration",
        }

        payload = self._request_osrm_json(url, params)
        durations = payload.get("durations") or []
        if len(durations) != len(sources):
            raise RoutingUnavailableError("OSRM /table returned an unexpected shape")
        try:
            return [[None if value is None else float(value) for value in row] for row in durations]
        except (TypeError, ValueError) as exc:
            raise RoutingUnavailableError(f"OSRM /table returned malformed durations: {exc}") from exc

    @classmethod
    def _store_duration_cell(cls, source: RoutePoint, destination: RoutePoint, profile: str, value: float) -> None:
        cache = cls._shared_duration_cache
        key = _duration_cell_key(source, destination, profile)
        if key not in cache and len(cache) >= cls._SHARED_DURATION_CACHE_CAP:
            cache.pop(next(iter(cache)), None)
        cache[key] = value

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

        leg: RoutingLeg | None = None
        if self._osrm_attemptable():
            try:
                leg = self._fetch_osrm_route(
                    start=start,
                    end=end,
                    pace=pace,
                    profile=profile,
                    apply_pace_multiplier=apply_pace_multiplier,
                )

                self._record_osrm_success()
            except RoutingUnavailableError:
                self._record_osrm_failure()
                leg = None
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

        coordinates = f"{start.lon},{start.lat};{end.lon},{end.lat}"
        url = f"{self.base_url}/route/v1/{profile}/{coordinates}"
        params = {
            "overview": "full",
            "geometries": "geojson",
            "steps": "false",
        }

        payload = self._request_osrm_json(url, params)
        try:
            route = (payload.get("routes") or [None])[0]
            if not route:
                return None

            geometry = route.get("geometry") or {}
            raw_coordinates = geometry.get("coordinates") or []
            route_coordinates = [
                {"lat": float(lat), "lon": float(lon)}
                for lon, lat in raw_coordinates
                if lat is not None and lon is not None
            ]
            if len(route_coordinates) < 2:
                return None

            duration_seconds = float(route.get("duration") or 0)
            distance_meters = float(route.get("distance") or 0)
            if duration_seconds <= 0 or distance_meters <= 0:
                return None

            return RoutingLeg(
                duration_seconds=(
                    duration_seconds * pace_duration_multiplier(pace) if apply_pace_multiplier else duration_seconds
                ),
                distance_meters=distance_meters,
                geometry=route_coordinates,
                source="osrm",
            )
        except (TypeError, KeyError, ValueError):
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


def _duration_cell_key(
    source: RoutePoint, destination: RoutePoint, profile: str
) -> tuple[float, float, float, float, str]:
    return (
        round(source.lat, 6),
        round(source.lon, 6),
        round(destination.lat, 6),
        round(destination.lon, 6),
        profile,
    )


def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    r = 6371.0
    dlat = radians(lat2 - lat1)
    dlon = radians(lon2 - lon1)
    a = sin(dlat / 2) ** 2 + cos(radians(lat1)) * cos(radians(lat2)) * sin(dlon / 2) ** 2
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
