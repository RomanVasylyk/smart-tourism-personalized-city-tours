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
