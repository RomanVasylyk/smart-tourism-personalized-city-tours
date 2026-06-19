from __future__ import annotations

from dataclasses import dataclass, replace
from datetime import date

from psycopg import Error as PsycopgError

from app.db.database import get_connection
from app.services.city_lookup import find_city_row
from app.services.routing_service import RoutePoint


@dataclass(frozen=True)
class GraphStop:
    stop_id: int
    name: str
    point: RoutePoint


@dataclass(frozen=True)
class GraphEdge:
    line_id: int
    line_name: str
    source: str
    from_stop_id: int
    to_stop_id: int
    from_stop_name: str
    to_stop_name: str
    from_sequence: int
    to_sequence: int
    avg_travel_seconds: float
    distance_meters: float


@dataclass(frozen=True)
class GraphTripStopTime:
    stop_id: int
    stop_name: str
    stop_sequence: int
    time_minutes: int


@dataclass(frozen=True)
class GraphTrip:
    trip_id: int
    line_id: int
    line_name: str
    source: str
    service_bucket: str
    valid_from: date | None
    valid_to: date | None
    stop_times: list[GraphTripStopTime]


@dataclass(frozen=True)
class TripBoardOption:
    trip_id: int
    stop_index: int


@dataclass(frozen=True)
class TransportGraph:
    provider: str
    stops_by_id: dict[int, GraphStop]
    outgoing_edges: dict[int, list[GraphEdge]]
    trips_by_id: dict[int, GraphTrip]
    board_options_by_stop: dict[int, list[TripBoardOption]]


_TRANSPORT_GRAPH_CACHE: dict[str, TransportGraph | None] = {}


def transport_cache_key(city_profile: dict) -> str | None:
    transport_profile = city_profile.get("transport") or {}
    provider = transport_profile.get("provider")
    city_name = city_profile.get("name")
    country = city_profile.get("country")
    if not provider or not city_name:
        return None
    return f"{str(city_name).strip().lower()}|{str(country or '').strip().lower()}|{str(provider).strip().lower()}"


def stop_display_name(name: str, platform_ref: str | None) -> str:
    if platform_ref:
        return f"{name} ({platform_ref})"
    return name


def load_transport_graph(city_profile: dict) -> TransportGraph | None:
    cache_key = transport_cache_key(city_profile)
    if cache_key is None:
        return None
    if cache_key in _TRANSPORT_GRAPH_CACHE:
        return _TRANSPORT_GRAPH_CACHE[cache_key]

    city_name = str(city_profile.get("name") or "").strip()
    country = str(city_profile.get("country") or "").strip()
    graph: TransportGraph | None = None

    try:
        with get_connection() as conn:
            with conn.cursor() as cur:
                city_row = find_city_row(cur, city_name, country=country)
                if not city_row:
                    return None

                city_id = city_row["id"]
                cur.execute(
                    """
                    SELECT id, name, platform_ref, lat, lon
                    FROM transport_stops
                    WHERE city_id = %s
                      AND is_active = TRUE;
                    """,
                    (city_id,),
                )
                stop_rows = cur.fetchall()
                stops_by_id = {
                    row["id"]: GraphStop(
                        stop_id=row["id"],
                        name=stop_display_name(row["name"], row.get("platform_ref")),
                        point=RoutePoint(lat=float(row["lat"]), lon=float(row["lon"])),
                    )
                    for row in stop_rows
                }
                if not stops_by_id:
                    return None

                cur.execute(
                    """
                    SELECT
                        tc.line_id,
                        tl.name AS line_name,
                        tl.provider AS provider,
                        tc.from_stop_id,
                        tc.to_stop_id,
                        tc.from_sequence,
                        tc.to_sequence,
                        tc.avg_travel_seconds,
                        tc.distance_meters
                    FROM transport_connections tc
                    JOIN transport_lines tl ON tl.id = tc.line_id
                    WHERE tc.city_id = %s
                      AND tc.is_active = TRUE
                      AND tl.is_active = TRUE
                    ORDER BY tc.line_id, tc.from_sequence, tc.to_sequence;
                    """,
                    (city_id,),
                )
                connection_rows = cur.fetchall()

                cur.execute(
                    """
                    SELECT
                        tt.id AS trip_id,
                        tt.service_bucket,
                        tt.source_url,
                        tt.valid_from,
                        tt.valid_to,
                        tl.id AS line_id,
                        tl.name AS line_name,
                        tl.provider AS provider,
                        ttst.stop_id,
                        ttst.stop_sequence,
                        ttst.time_minutes
                    FROM transport_trips tt
                    JOIN transport_lines tl ON tl.id = tt.line_id
                    JOIN transport_trip_stop_times ttst ON ttst.trip_id = tt.id
                    WHERE tl.city_id = %s
                      AND tl.is_active = TRUE
                      AND tt.is_active = TRUE
                    ORDER BY tt.id, ttst.stop_sequence;
                    """,
                    (city_id,),
                )
                trip_rows = cur.fetchall()

        outgoing_edges: dict[int, list[GraphEdge]] = {}
        provider = None
        for row in connection_rows:
            if row["from_stop_id"] not in stops_by_id or row["to_stop_id"] not in stops_by_id:
                continue

            from_stop = stops_by_id[row["from_stop_id"]]
            to_stop = stops_by_id[row["to_stop_id"]]
            edge = GraphEdge(
                line_id=int(row["line_id"]),
                line_name=row["line_name"],
                source=row["provider"],
                from_stop_id=int(row["from_stop_id"]),
                to_stop_id=int(row["to_stop_id"]),
                from_stop_name=from_stop.name,
                to_stop_name=to_stop.name,
                from_sequence=int(row["from_sequence"]),
                to_sequence=int(row["to_sequence"]),
                avg_travel_seconds=float(row["avg_travel_seconds"]),
                distance_meters=float(row["distance_meters"]),
            )
            outgoing_edges.setdefault(edge.from_stop_id, []).append(edge)
            provider = provider or edge.source

        trips_by_id: dict[int, GraphTrip] = {}
        trip_stop_times_by_id: dict[int, list[GraphTripStopTime]] = {}
        for row in trip_rows:
            stop_id = int(row["stop_id"])
            if stop_id not in stops_by_id:
                continue

            trip_id = int(row["trip_id"])
            trip_stop_times_by_id.setdefault(trip_id, []).append(
                GraphTripStopTime(
                    stop_id=stop_id,
                    stop_name=stops_by_id[stop_id].name,
                    stop_sequence=int(row["stop_sequence"]),
                    time_minutes=int(row["time_minutes"]),
                )
            )
            if trip_id not in trips_by_id:
                trips_by_id[trip_id] = GraphTrip(
                    trip_id=trip_id,
                    line_id=int(row["line_id"]),
                    line_name=row["line_name"],
                    source=row["source_url"] or row["provider"],
                    service_bucket=row["service_bucket"],
                    valid_from=row["valid_from"],
                    valid_to=row["valid_to"],
                    stop_times=[],
                )
                provider = provider or row["provider"]

        board_options_by_stop: dict[int, list[TripBoardOption]] = {}
        for trip_id, trip in list(trips_by_id.items()):
            stop_times = trip_stop_times_by_id.get(trip_id, [])
            stop_times.sort(key=lambda item: item.stop_sequence)
            if len(stop_times) < 2:
                del trips_by_id[trip_id]
                continue

            trips_by_id[trip_id] = replace(trip, stop_times=stop_times)
            for index, stop_time in enumerate(stop_times[:-1]):
                board_options_by_stop.setdefault(stop_time.stop_id, []).append(
                    TripBoardOption(
                        trip_id=trip_id,
                        stop_index=index,
                    )
                )

        for stop_id, options in board_options_by_stop.items():
            options.sort(
                key=lambda option: trips_by_id[option.trip_id].stop_times[option.stop_index].time_minutes,
            )

        if outgoing_edges or trips_by_id:
            graph = TransportGraph(
                provider=provider or str((city_profile.get("transport") or {}).get("provider") or "transport_provider"),
                stops_by_id=stops_by_id,
                outgoing_edges=outgoing_edges,
                trips_by_id=trips_by_id,
                board_options_by_stop=board_options_by_stop,
            )
    except PsycopgError:
        graph = None

    if graph is not None:
        _TRANSPORT_GRAPH_CACHE[cache_key] = graph
    return graph
