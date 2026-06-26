from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass
class StopRow:
    name: str
    times: list[int | None]


@dataclass
class VariantAccumulator:
    line_number: str
    service_bucket: str
    source_urls: set[str] = field(default_factory=set)
    valid_from: str | None = None
    valid_to: str | None = None
    stop_names: list[str] = field(default_factory=list)
    edge_samples: list[list[float]] = field(default_factory=list)
    trip_columns: list[list[int | None]] = field(default_factory=list)


@dataclass(frozen=True)
class MatchedStopAssignment:
    original_index: int
    stop_record: dict[str, Any]
    provider_stop_name: str

    @property
    def graph_stop_key(self) -> str:
        return str(self.stop_record["graph_stop_key"])

    @property
    def name(self) -> str:
        return str(self.stop_record["name"])

    @property
    def lat(self) -> float:
        return float(self.stop_record["lat"])

    @property
    def lon(self) -> float:
        return float(self.stop_record["lon"])


@dataclass
class TripStopDraft:
    sequence: int
    graph_stop_key: str
    provider_stop_name: str
    time_minutes: int

    def to_record(self) -> GraphTripStopTimeRecord:
        return GraphTripStopTimeRecord(
            sequence=self.sequence,
            graph_stop_key=self.graph_stop_key,
            provider_stop_name=self.provider_stop_name,
            time_minutes=self.time_minutes,
        )


@dataclass
class GraphBuildMetrics:
    variant_count: int
    invalid_trip_count: int = 0
    dropped_trip_count: int = 0
    descending_time_trip_count: int = 0
    duplicate_consecutive_stop_count: int = 0
    invalid_validity_count: int = 0
    empty_service_bucket_count: int = 0
    line_without_trip_count: int = 0

    def to_dict(self) -> dict[str, int]:
        return {
            "variant_count": self.variant_count,
            "invalid_trip_count": self.invalid_trip_count,
            "dropped_trip_count": self.dropped_trip_count,
            "descending_time_trip_count": self.descending_time_trip_count,
            "duplicate_consecutive_stop_count": self.duplicate_consecutive_stop_count,
            "invalid_validity_count": self.invalid_validity_count,
            "empty_service_bucket_count": self.empty_service_bucket_count,
            "line_without_trip_count": self.line_without_trip_count,
        }


@dataclass
class UnmatchedStopRecord:
    stop_name: str
    normalized_name: str
    occurrences: int = 0
    line_numbers: set[str] = field(default_factory=set)
    source_urls: set[str] = field(default_factory=set)

    def to_dict(self) -> dict[str, Any]:
        return {
            "stop_name": self.stop_name,
            "normalized_name": self.normalized_name,
            "occurrences": self.occurrences,
            "line_numbers": sorted(self.line_numbers),
            "source_urls": sorted(self.source_urls),
        }


@dataclass(frozen=True)
class GraphStopRecord:
    graph_stop_key: str
    name: str
    normalized_name: str
    lat: float
    lon: float
    platform_ref: str | None
    source: str
    source_reference: str | None
    matched_by: str | None

    def to_dict(self) -> dict[str, Any]:
        return {
            "graph_stop_key": self.graph_stop_key,
            "name": self.name,
            "normalized_name": self.normalized_name,
            "lat": self.lat,
            "lon": self.lon,
            "platform_ref": self.platform_ref,
            "source": self.source,
            "source_reference": self.source_reference,
            "matched_by": self.matched_by,
        }


@dataclass(frozen=True)
class GraphLineStopRecord:
    sequence: int
    provider_stop_name: str
    graph_stop_key: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "sequence": self.sequence,
            "provider_stop_name": self.provider_stop_name,
            "graph_stop_key": self.graph_stop_key,
        }


@dataclass(frozen=True)
class GraphLineRecord:
    line_id: str
    provider_line_id: str
    name: str
    direction_name: str
    service_bucket: str
    source_url: str
    valid_from: str | None
    valid_to: str | None
    stops: list[GraphLineStopRecord]

    def to_dict(self) -> dict[str, Any]:
        return {
            "line_id": self.line_id,
            "provider_line_id": self.provider_line_id,
            "name": self.name,
            "direction_name": self.direction_name,
            "service_bucket": self.service_bucket,
            "source_url": self.source_url,
            "valid_from": self.valid_from,
            "valid_to": self.valid_to,
            "stops": [stop.to_dict() for stop in self.stops],
        }


@dataclass(frozen=True)
class GraphConnectionRecord:
    line_id: str
    source_url: str
    from_sequence: int
    to_sequence: int
    from_stop_key: str
    to_stop_key: str
    avg_travel_seconds: float
    distance_meters: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "line_id": self.line_id,
            "source_url": self.source_url,
            "from_sequence": self.from_sequence,
            "to_sequence": self.to_sequence,
            "from_stop_key": self.from_stop_key,
            "to_stop_key": self.to_stop_key,
            "avg_travel_seconds": self.avg_travel_seconds,
            "distance_meters": self.distance_meters,
        }


@dataclass(frozen=True)
class GraphTripStopTimeRecord:
    sequence: int
    graph_stop_key: str
    provider_stop_name: str
    time_minutes: int

    def to_dict(self) -> dict[str, Any]:
        return {
            "sequence": self.sequence,
            "graph_stop_key": self.graph_stop_key,
            "provider_stop_name": self.provider_stop_name,
            "time_minutes": self.time_minutes,
        }


@dataclass(frozen=True)
class GraphTripRecord:
    trip_id: str
    line_id: str
    service_bucket: str
    source_url: str
    valid_from: str | None
    valid_to: str | None
    stop_times: list[GraphTripStopTimeRecord]

    def to_dict(self) -> dict[str, Any]:
        return {
            "trip_id": self.trip_id,
            "line_id": self.line_id,
            "service_bucket": self.service_bucket,
            "source_url": self.source_url,
            "valid_from": self.valid_from,
            "valid_to": self.valid_to,
            "stop_times": [stop_time.to_dict() for stop_time in self.stop_times],
        }


@dataclass(frozen=True)
class TransportGraphData:
    city: str
    provider: str
    generated_at: str
    stops: list[GraphStopRecord]
    lines: list[GraphLineRecord]
    connections: list[GraphConnectionRecord]
    trips: list[GraphTripRecord]
    unmatched_stops: list[str]

    def to_dict(self) -> dict[str, Any]:
        return {
            "city": self.city,
            "provider": self.provider,
            "generated_at": self.generated_at,
            "stops": [stop.to_dict() for stop in self.stops],
            "lines": [line.to_dict() for line in self.lines],
            "connections": [connection.to_dict() for connection in self.connections],
            "trips": [trip.to_dict() for trip in self.trips],
            "unmatched_stops": self.unmatched_stops,
        }


@dataclass(frozen=True)
class TransportQualityReport:
    city: str | None
    provider: str | None
    generated_at: str | None
    source_document_count: int
    parsed_document_count: int
    variant_count: int
    total_stops: int
    matched_stops: int
    unmatched_stop_count: int
    total_lines: int
    total_connections: int
    total_trips: int
    total_stop_times: int
    invalid_trip_count: int
    dropped_trip_count: int
    descending_time_trip_count: int
    duplicate_consecutive_stop_count: int
    invalid_stop_times: int
    invalid_validity_count: int
    empty_service_bucket_count: int
    line_without_trip_count: int
    warnings_count: int
    coverage_ratio: float

    def to_dict(self) -> dict[str, Any]:
        return {
            "city": self.city,
            "provider": self.provider,
            "generated_at": self.generated_at,
            "source_document_count": self.source_document_count,
            "parsed_document_count": self.parsed_document_count,
            "variant_count": self.variant_count,
            "total_stops": self.total_stops,
            "matched_stops": self.matched_stops,
            "unmatched_stop_count": self.unmatched_stop_count,
            "total_lines": self.total_lines,
            "total_connections": self.total_connections,
            "total_trips": self.total_trips,
            "total_stop_times": self.total_stop_times,
            "invalid_trip_count": self.invalid_trip_count,
            "dropped_trip_count": self.dropped_trip_count,
            "descending_time_trip_count": self.descending_time_trip_count,
            "duplicate_consecutive_stop_count": self.duplicate_consecutive_stop_count,
            "invalid_stop_times": self.invalid_stop_times,
            "invalid_validity_count": self.invalid_validity_count,
            "empty_service_bucket_count": self.empty_service_bucket_count,
            "line_without_trip_count": self.line_without_trip_count,
            "warnings_count": self.warnings_count,
            "coverage_ratio": self.coverage_ratio,
        }


@dataclass
class TransportIssue:
    code: str
    message: str
    severity: str = "warning"
    document: str | None = None
    line_number: str | None = None
    page: int | None = None
    stop_name: str | None = None
    trip_id: str | None = None
    details: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        payload = {
            "severity": self.severity,
            "code": self.code,
            "message": self.message,
        }
        if self.document is not None:
            payload["document"] = self.document
        if self.line_number is not None:
            payload["line_number"] = self.line_number
        if self.page is not None:
            payload["page"] = self.page
        if self.stop_name is not None:
            payload["stop_name"] = self.stop_name
        if self.trip_id is not None:
            payload["trip_id"] = self.trip_id
        if self.details:
            payload["details"] = self.details
        return payload
