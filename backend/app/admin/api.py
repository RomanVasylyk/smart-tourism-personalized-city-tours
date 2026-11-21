from __future__ import annotations

import uuid
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from typing import Annotated, Any, Literal

from app.admin import crud
from app.admin import pipeline as data_pipeline
from app.admin.resources import Resource, metadata
from app.core.config import get_settings
from app.core.rate_limit import AUTH_RATE_LIMIT, limiter
from app.db.database import get_connection
from app.repositories import users as user_repository
from app.services import auth
from app.services.feedback_stats import recompute_feedback_stats
from app.services.holidays import insert_statutory_holidays
from app.services.route_planning.opening_hours import ALWAYS_OPEN_VALUES, parse_opening_hours
from app.services.service_calendar import invalidate_service_calendar
from app.services.transport_graph import clear_transport_graph_cache
from fastapi import APIRouter, Body, Depends, HTTPException, Request, Response
from fastapi.responses import HTMLResponse
from pydantic import BaseModel, Field

STATIC_DIR = Path(__file__).resolve().parent / "static"
COOKIE_NAME = "admin_session"
ADMIN_TOKEN_LIFETIME = timedelta(hours=12)
ADMIN_HEADER = "x-admin-request"
SAFE_METHODS = {"GET", "HEAD", "OPTIONS"}
WEEKDAY_NAMES = ("Pondelok", "Utorok", "Streda", "Štvrtok", "Piatok", "Sobota", "Nedeľa")
PIPELINE_POI_FIELDS = (
    "name",
    "category",
    "subcategory",
    "lat",
    "lon",
    "address",
    "visit_duration_min",
    "base_score",
    "short_description",
    "website",
    "image_url",
    "wikidata_id",
    "wikipedia_title",
    "wikipedia_url",
    "is_active",
)
TRANSPORT_RESOURCES = {"transport_lines", "transport_stops", "transport_trips", "transport_trip_stop_times"}

router = APIRouter(prefix="/admin", include_in_schema=False)


class AdminLoginRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=128)


class OpeningHoursCheckRequest(BaseModel):
    value: str = Field(default="", max_length=500)


class HolidayYearRequest(BaseModel):
    year: int = Field(ge=2000, le=2100)


class PipelineActionRequest(BaseModel):
    allow_errors: bool = False


def require_admin(request: Request) -> dict:
    user = auth.user_for_token(request.cookies.get(COOKIE_NAME))
    if user is None:
        raise crud.AdminError(401, "Prihláste sa.")
    if not user.get("is_admin"):
        raise crud.AdminError(403, "Účet nemá oprávnenie správcu.")
    if request.method not in SAFE_METHODS and request.headers.get(ADMIN_HEADER) != "1":
        raise crud.AdminError(403, "Požiadavka nepochádza z administrácie.")
    return user


AdminUser = Annotated[dict, Depends(require_admin)]


@router.get("")
@router.get("/")
def admin_page():
    version = int(max((STATIC_DIR / name).stat().st_mtime for name in ("admin.js", "admin.css")))
    html = (STATIC_DIR / "index.html").read_text(encoding="utf-8")
    for asset in ("admin.css", "admin.js"):
        html = html.replace(f"/admin/static/{asset}", f"/admin/static/{asset}?v={version}")
    return HTMLResponse(html, headers={"Cache-Control": "no-store"})


@router.post("/api/login")
@limiter.limit(AUTH_RATE_LIMIT)
def admin_login(request: Request, response: Response, payload: AdminLoginRequest):
    user = auth.authenticate(payload.email, payload.password)
    if not user.get("is_admin"):
        raise crud.AdminError(403, "Účet nemá oprávnenie správcu.")
    token = auth.issue_token(user, ADMIN_TOKEN_LIFETIME)
    response.set_cookie(
        COOKIE_NAME,
        token,
        max_age=int(ADMIN_TOKEN_LIFETIME.total_seconds()),
        httponly=True,
        samesite="strict",
        secure=get_settings().admin_cookie_secure,
        path="/admin",
    )
    return {"user": auth.public_user(user)}


@router.post("/api/logout")
def admin_logout(request: Request, response: Response, admin: AdminUser):
    token = request.cookies.get(COOKIE_NAME)
    if token:
        user_repository.delete_token(auth.hash_token(token))
    response.delete_cookie(COOKIE_NAME, path="/admin")
    return {"ok": True}


@router.get("/api/me")
def admin_me(admin: AdminUser):
    return {"user": auth.public_user(admin)}


@router.get("/api/meta")
def admin_meta(admin: AdminUser):
    return {"resources": metadata()}


def query_filters(request: Request) -> dict[str, str]:
    return {key[2:]: value for key, value in request.query_params.items() if key.startswith("f_")}


@router.get("/api/r/{name}")
def admin_list(
    name: str,
    request: Request,
    admin: AdminUser,
    q: str | None = None,
    sort: str | None = None,
    desc: bool | None = None,
    page: int = 1,
    page_size: int = 50,
):
    return crud.list_rows(
        crud.get_resource(name),
        search=q,
        filters=query_filters(request),
        sort=sort,
        descending=desc,
        page=page,
        page_size=page_size,
    )


@router.get("/api/r/{name}/{key}")
def admin_detail(name: str, key: str, admin: AdminUser):
    return crud.get_row(crud.get_resource(name), key)


def admin_email(value: str) -> str:
    try:
        return auth.normalize_email(value)
    except HTTPException as exc:
        raise crud.AdminError(422, "E-mailová adresa nie je platná.") from exc


def admin_password(password: str) -> None:
    if len(password) < auth.MIN_PASSWORD_LENGTH:
        raise crud.AdminError(422, f"Heslo musí mať aspoň {auth.MIN_PASSWORD_LENGTH} znakov.")


def prepare_values(
    resource: Resource,
    values: dict[str, Any],
    data: dict[str, Any],
    *,
    admin: dict,
    existing: dict | None,
) -> dict[str, Any]:
    creating = existing is None
    if resource.name == "pois":
        if "opening_hours_override" in values:
            override = crud.validate_opening_hours(values["opening_hours_override"])
            values["opening_hours_override"] = override
            if creating or override != existing.get("opening_hours_override"):
                values["opening_hours_override_by"] = admin["email"] if override else None
                values["opening_hours_override_at"] = datetime.now(UTC) if override else None
        score = values.get("base_score")
        if score is not None and not 0.0 <= score <= 1.0:
            raise crud.AdminError(422, "Základné skóre musí byť medzi 0 a 1.")
        visit = values.get("visit_duration_min")
        if visit is not None and not 1 <= visit <= 600:
            raise crud.AdminError(422, "Dĺžka návštevy musí byť od 1 do 600 minút.")
        if not creating and existing.get("osm_type") != "manual":
            changed = [name for name in PIPELINE_POI_FIELDS if name in values and values[name] != existing.get(name)]
            unlocking = existing.get("admin_locked") and values.get("admin_locked") is False
            if changed and not unlocking:
                values["admin_locked"] = True
        if creating:
            values.setdefault("osm_type", "manual")
            values.setdefault("osm_id", f"manual-{uuid.uuid4().hex[:12]}")
            values.setdefault("source", "admin")
            values.setdefault("is_active", True)
            values.setdefault("visit_duration_min", 20)
            values.setdefault("base_score", 0.5)
    for column_name, limit in (("lat", 90.0), ("lon", 180.0), ("center_lat", 90.0), ("center_lon", 180.0)):
        value = values.get(column_name)
        if value is not None and abs(value) > limit:
            raise crud.AdminError(422, "Súradnice sú mimo povoleného rozsahu.")
    if resource.name == "school_holidays":
        start = values.get("start_date", existing.get("start_date") if existing else None)
        end = values.get("end_date", existing.get("end_date") if existing else None)
        if start and end and end < start:
            raise crud.AdminError(422, "Posledný deň prázdnin nemôže byť pred prvým dňom.")
    if resource.name == "curated_routes" and not creating:
        values["updated_at"] = datetime.now(UTC)
    if resource.name == "users":
        if "email" in values:
            values["email"] = admin_email(values["email"])
        password = (data.get("password") or "").strip()
        if creating:
            if not password:
                raise crud.AdminError(422, "Pri novom účte zadajte heslo.")
            admin_password(password)
            values["password_hash"] = auth.hash_password(password)
        elif existing["id"] == admin["id"] and (values.get("is_admin") is False or values.get("is_active") is False):
            raise crud.AdminError(422, "Vlastnému účtu nemôžete odobrať práva správcu ani ho deaktivovať.")
    return values


def after_change(resource: Resource) -> None:
    if resource.name in ("holidays", "school_holidays", "cities"):
        invalidate_service_calendar()
    if resource.name in TRANSPORT_RESOURCES:
        clear_transport_graph_cache()


@router.post("/api/r/{name}")
def admin_create(name: str, data: Annotated[dict[str, Any], Body()], admin: AdminUser):
    resource = crud.get_resource(name)
    if not resource.can_create:
        raise crud.AdminError(405, "Tento typ záznamu nemožno vytvárať.")
    values = prepare_values(
        resource, crud.collect_values(resource, data, creating=True), data, admin=admin, existing=None
    )
    created = crud.insert_row(resource, values)
    after_change(resource)
    return {"key": "~".join(str(created[name]) for name in resource.pk)}


@router.patch("/api/r/{name}/{key}")
def admin_update(name: str, key: str, data: Annotated[dict[str, Any], Body()], admin: AdminUser):
    resource = crud.get_resource(name)
    if not resource.can_edit:
        raise crud.AdminError(405, "Tento typ záznamu nemožno upravovať.")
    existing = crud.get_row(resource, key)["row"]
    new_password = (data.get("password") or "").strip() if resource.name == "users" else ""
    if new_password:
        admin_password(new_password)
    values = prepare_values(
        resource, crud.collect_values(resource, data, creating=False), data, admin=admin, existing=existing
    )
    crud.update_row(resource, key, values)
    if new_password:
        user_repository.set_password_hash(existing["id"], auth.hash_password(new_password))
    after_change(resource)
    return {"ok": True}


@router.delete("/api/r/{name}/{key}")
def admin_delete(name: str, key: str, admin: AdminUser):
    resource = crud.get_resource(name)
    if not resource.can_delete:
        raise crud.AdminError(405, "Tento typ záznamu nemožno mazať.")
    if resource.name == "users" and key == str(admin["id"]):
        raise crud.AdminError(422, "Vlastný účet nemôžete vymazať.")
    crud.delete_row(resource, key)
    after_change(resource)
    return {"ok": True}


@router.get("/api/options/{name}")
def admin_options(name: str, admin: AdminUser, q: str | None = None, ids: str | None = None):
    id_list = [item for item in (ids or "").split(",") if item]
    return {"options": crud.options(crud.get_resource(name), q, id_list)}


def format_minutes(value: int) -> str:
    return f"{value // 60:02d}:{value % 60:02d}"


@router.post("/api/opening-hours/check")
def admin_check_opening_hours(payload: OpeningHoursCheckRequest, admin: AdminUser):
    value = payload.value.strip()
    if not value:
        return {
            "valid": True,
            "normalized": None,
            "week": [],
            "message": "Použijú sa hodiny z OSM, ak chýbajú, bod je prístupný nonstop.",
        }
    try:
        normalized = crud.validate_opening_hours(value)
    except crud.AdminError as exc:
        return {"valid": False, "normalized": None, "week": [], "message": exc.detail}
    today = date.today()
    week = []
    for offset in range(7):
        day = today + timedelta(days=offset)
        if normalized.lower() in ALWAYS_OPEN_VALUES:
            intervals = [(0, 24 * 60)]
            text = "nonstop"
        else:
            schedule = parse_opening_hours(normalized, day) or {}
            intervals = schedule.get(day.weekday(), [])
            text = (
                ", ".join(f"{format_minutes(start)}–{format_minutes(end)}" for start, end in intervals) or "zatvorené"
            )
        week.append(
            {
                "date": day.isoformat(),
                "day": WEEKDAY_NAMES[day.weekday()],
                "hours": text,
                "intervals": [[start, end] for start, end in intervals],
            }
        )
    return {"valid": True, "normalized": normalized, "week": week, "message": "Zápis je platný."}


@router.post("/api/holidays/generate")
def admin_generate_holidays(payload: HolidayYearRequest, admin: AdminUser):
    with get_connection() as conn:
        with conn.cursor() as cur:
            inserted = insert_statutory_holidays(cur, payload.year)
        conn.commit()
    invalidate_service_calendar()
    return {"inserted": inserted, "year": payload.year}


@router.post("/api/feedback/recompute")
def admin_recompute_feedback(admin: AdminUser):
    return {"summary": recompute_feedback_stats()}


@router.post("/api/transport/reload")
def admin_reload_transport(admin: AdminUser):
    clear_transport_graph_cache()
    invalidate_service_calendar()
    return {"ok": True}


@router.get("/api/pipeline")
def admin_pipeline_status(admin: AdminUser):
    return data_pipeline.status()


@router.post("/api/pipeline/{slug}/{dataset}/{action}")
def admin_pipeline_action(
    slug: str,
    dataset: Literal["transport", "pois"],
    action: Literal["check", "approve", "load"],
    admin: AdminUser,
    payload: Annotated[PipelineActionRequest | None, Body()] = None,
):
    allow_errors = bool(payload and payload.allow_errors)
    try:
        known = data_pipeline.city_slugs()
    except data_pipeline.PipelineUnavailableError as exc:
        raise crud.AdminError(503, str(exc)) from exc
    if slug not in known:
        raise crud.AdminError(404, "Mesto nie je v konfigurácii pipeline.")
    handlers = {
        ("transport", "check"): lambda: data_pipeline.check_transport(slug),
        ("transport", "approve"): lambda: data_pipeline.approve_transport(slug, admin),
        ("transport", "load"): lambda: data_pipeline.load_transport(slug, admin),
        ("pois", "check"): lambda: data_pipeline.check_pois(slug),
        ("pois", "approve"): lambda: data_pipeline.approve_pois(slug, admin, allow_errors),
        ("pois", "load"): lambda: data_pipeline.load_pois(slug, admin),
    }
    try:
        return {"result": handlers[(dataset, action)]()}
    except data_pipeline.PipelineUnavailableError as exc:
        raise crud.AdminError(503, str(exc)) from exc
    except FileNotFoundError as exc:
        raise crud.AdminError(404, "Spracovaný súbor neexistuje. Najprv spustite dátovú pipeline.") from exc
    except ValueError as exc:
        if data_pipeline.error_kind(exc) == "review":
            raise crud.AdminError(
                422, "Kontrola našla chyby. Opravte ich v pipeline alebo potvrďte schválenie aj napriek chybám."
            ) from exc
        raise
    except RuntimeError as exc:
        if data_pipeline.error_kind(exc) == "approval":
            raise crud.AdminError(409, "Dáta nie sú schválené alebo sa po schválení zmenili.") from exc
        raise


@router.get("/api/dashboard")
def admin_dashboard(admin: AdminUser):
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT
                    (SELECT COUNT(*) FROM cities) AS cities,
                    (SELECT COUNT(*) FROM pois WHERE is_active) AS active_pois,
                    (SELECT COUNT(*) FROM pois WHERE NOT is_active) AS inactive_pois,
                    (SELECT COUNT(*) FROM pois WHERE opening_hours_override IS NOT NULL) AS manual_hours,
                    (SELECT COUNT(*) FROM pois WHERE opening_hours_override IS NULL AND opening_hours_raw IS NULL) AS pois_without_hours,
                    (SELECT COUNT(*) FROM users) AS users,
                    (SELECT COUNT(*) FROM route_sessions) AS sessions,
                    (SELECT COUNT(*) FROM route_sessions WHERE status = 'completed') AS completed_sessions,
                    (SELECT COUNT(*) FROM route_feedback) AS feedback,
                    (SELECT ROUND(AVG(rating)::numeric, 2) FROM route_feedback) AS average_rating,
                    (SELECT COUNT(*) FROM curated_routes WHERE is_active) AS curated_routes,
                    (SELECT COUNT(*) FROM transport_lines WHERE is_active) AS transport_lines
                """
            )
            counts = cur.fetchone()
            cur.execute(
                """
                SELECT holiday_date, name FROM holidays
                WHERE is_active AND holiday_date >= CURRENT_DATE
                ORDER BY holiday_date LIMIT 5
                """
            )
            holidays = cur.fetchall()
            cur.execute(
                """
                SELECT s.name, s.start_date, s.end_date,
                       bool_or(s.city_id IS NULL) AS everywhere,
                       string_agg(c.name, ', ' ORDER BY c.name) AS cities
                FROM school_holidays s LEFT JOIN cities c ON c.id = s.city_id
                WHERE s.is_active AND s.end_date >= CURRENT_DATE
                GROUP BY s.name, s.start_date, s.end_date
                ORDER BY s.start_date LIMIT 6
                """
            )
            school_holidays = cur.fetchall()
            cur.execute(
                """
                SELECT c.name, COUNT(p.id) FILTER (WHERE p.is_active) AS pois
                FROM cities c LEFT JOIN pois p ON p.city_id = c.id
                GROUP BY c.id, c.name ORDER BY c.name
                """
            )
            cities = cur.fetchall()
    return {"counts": counts, "holidays": holidays, "school_holidays": school_holidays, "cities": cities}
