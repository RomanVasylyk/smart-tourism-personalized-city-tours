from datetime import datetime, timezone
from hashlib import sha256
from uuid import UUID, uuid4

from fastapi import HTTPException

from app.repositories import route_sessions as route_session_repository
from app.schemas.route_sessions import (
    MAX_DEVICE_ID_LENGTH,
    MAX_DEVICE_TOKEN_LENGTH,
    RouteFeedbackRequest,
    RouteSessionCreateRequest,
    RouteSessionPoiVisitRequest,
    RouteSessionUpdateRequest,
)

SESSION_STATUSES = {"not_started", "in_progress", "paused", "completed", "cancelled"}


def create_route_session(
    request: RouteSessionCreateRequest,
    authenticated_device_id: str,
    authenticated_device_token: str,
) -> dict:
    validate_status(request.status)
    device_id = require_matching_device_id(request.device_id, authenticated_device_id)
    device_token_hash = hash_authenticated_device_token(authenticated_device_token)
    city_id = route_session_repository.find_city_id(request.city)
    if city_id is None:
        raise HTTPException(status_code=404, detail="City not found.")

    session_id = request.id or uuid4()
    started_at = request.started_at or now_utc()
    was_saved = route_session_repository.upsert_route_session(
        session_id=session_id,
        device_id=device_id,
        device_token_hash=device_token_hash,
        city_id=city_id,
        status=request.status,
        start_lat=request.start_lat,
        start_lon=request.start_lon,
        available_minutes=request.available_minutes,
        pace=request.pace,
        return_to_start=request.return_to_start,
        opening_hours_enabled=request.opening_hours_enabled,
        started_at=started_at,
        finished_at=request.finished_at,
        used_minutes=request.used_minutes,
        total_walk_minutes=request.total_walk_minutes,
        total_visit_minutes=request.total_visit_minutes,
        route_snapshot_json=request.route_snapshot_json,
    )
    if not was_saved:
        raise_not_found()

    return get_route_session(session_id, device_id, authenticated_device_token)


def update_route_session(
    session_id: UUID,
    request: RouteSessionUpdateRequest,
    authenticated_device_id: str,
    authenticated_device_token: str,
) -> dict:
    device_id = require_authenticated_device_id(authenticated_device_id)
    device_token_hash = hash_authenticated_device_token(authenticated_device_token)
    update_values = request.model_dump(exclude_unset=True, exclude_none=True)
    if not update_values:
        return get_route_session(session_id, device_id, authenticated_device_token)

    if "status" in update_values:
        validate_status(update_values["status"])

    was_updated = route_session_repository.update_route_session(
        session_id=session_id,
        device_id=device_id,
        device_token_hash=device_token_hash,
        update_values=update_values,
    )
    if not was_updated:
        raise_not_found()

    return get_route_session(session_id, device_id, authenticated_device_token)


def mark_route_session_poi_visited(
    session_id: UUID,
    poi_id: int,
    request: RouteSessionPoiVisitRequest,
    authenticated_device_id: str,
    authenticated_device_token: str,
) -> dict:
    device_id = require_authenticated_device_id(authenticated_device_id)
    device_token_hash = hash_authenticated_device_token(authenticated_device_token)
    visited_at = request.visited_at or now_utc()
    poi_row = route_session_repository.mark_route_session_poi_visited(
        session_id=session_id,
        poi_id=poi_id,
        device_id=device_id,
        device_token_hash=device_token_hash,
        visited_at=visited_at,
        skipped=request.skipped,
    )
    if poi_row is None:
        raise HTTPException(status_code=404, detail="Route session POI not found.")

    return poi_row


def save_route_feedback(
    session_id: UUID,
    request: RouteFeedbackRequest,
    authenticated_device_id: str,
    authenticated_device_token: str,
) -> dict:
    device_id = require_authenticated_device_id(authenticated_device_id)
    device_token_hash = hash_authenticated_device_token(authenticated_device_token)
    feedback = route_session_repository.replace_route_feedback(
        session_id=session_id,
        device_id=device_id,
        device_token_hash=device_token_hash,
        rating=request.rating,
        was_convenient=request.was_convenient,
        too_much_walking=request.too_much_walking,
        pois_were_interesting=request.pois_were_interesting,
        comment=request.comment,
    )
    if feedback is None:
        raise_not_found()

    return feedback


def get_route_session(
    session_id: UUID,
    authenticated_device_id: str,
    authenticated_device_token: str,
) -> dict:
    device_id = require_authenticated_device_id(authenticated_device_id)
    device_token_hash = hash_authenticated_device_token(authenticated_device_token)
    session = route_session_repository.get_route_session(
        session_id=session_id,
        device_id=device_id,
        device_token_hash=device_token_hash,
    )
    if session is None:
        raise_not_found()

    return session


def get_route_sessions_for_device(
    device_id: str,
    authenticated_device_id: str,
    authenticated_device_token: str,
) -> list[dict]:
    device_id = require_matching_device_id(device_id, authenticated_device_id)
    device_token_hash = hash_authenticated_device_token(authenticated_device_token)
    return route_session_repository.get_route_sessions_for_device(
        device_id=device_id,
        device_token_hash=device_token_hash,
    )


def require_authenticated_device_id(device_id: str | None) -> str:
    normalized_device_id = (device_id or "").strip()
    if not normalized_device_id:
        raise HTTPException(status_code=401, detail="X-Device-Id header is required.")
    if len(normalized_device_id) > MAX_DEVICE_ID_LENGTH:
        raise HTTPException(status_code=400, detail="Device id is too long.")
    return normalized_device_id


def require_authenticated_device_token(device_token: str | None) -> str:
    normalized_device_token = (device_token or "").strip()
    if not normalized_device_token:
        raise HTTPException(status_code=401, detail="X-Device-Token header is required.")
    if len(normalized_device_token) > MAX_DEVICE_TOKEN_LENGTH:
        raise HTTPException(status_code=400, detail="Device token is too long.")
    return normalized_device_token


def require_matching_device_id(device_id: str | None, authenticated_device_id: str | None) -> str:
    normalized_device_id = (device_id or "").strip()
    if not normalized_device_id:
        raise HTTPException(status_code=400, detail="Device id is required.")

    authenticated_device_id = require_authenticated_device_id(authenticated_device_id)
    if normalized_device_id != authenticated_device_id:
        raise HTTPException(status_code=403, detail="Device id does not match authenticated device.")

    return normalized_device_id


def hash_authenticated_device_token(device_token: str | None) -> str:
    normalized_device_token = require_authenticated_device_token(device_token)
    return sha256(normalized_device_token.encode("utf-8")).hexdigest()


def validate_status(status: str) -> None:
    if status not in SESSION_STATUSES:
        raise HTTPException(status_code=400, detail="Invalid route session status.")


def raise_not_found() -> None:
    raise HTTPException(status_code=404, detail="Route session not found.")


def now_utc() -> datetime:
    return datetime.now(timezone.utc)
