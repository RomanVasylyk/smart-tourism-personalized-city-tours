from app.core.rate_limit import AUTH_RATE_LIMIT, SESSION_RATE_LIMIT, limiter
from app.repositories import route_sessions as route_session_repository
from app.schemas.auth import (
    AuthResponse,
    LinkDeviceResponse,
    LoginRequest,
    RegisterRequest,
    StatusResponse,
    UserResponse,
)
from app.schemas.responses import RouteSessionResponse
from app.services import auth
from app.services.route_sessions import DEFAULT_SESSION_PAGE_SIZE, MAX_SESSION_PAGE_SIZE
from fastapi import APIRouter, Header, Request

router = APIRouter(prefix="/auth", tags=["auth"])


@router.post("/register", response_model=AuthResponse, status_code=201)
@limiter.limit(AUTH_RATE_LIMIT)
def register_endpoint(request: Request, payload: RegisterRequest):
    return auth.register_user(payload.email, payload.password, payload.display_name)


@router.post("/login", response_model=AuthResponse)
@limiter.limit(AUTH_RATE_LIMIT)
def login_endpoint(request: Request, payload: LoginRequest):
    return auth.login_user(payload.email, payload.password)


@router.post("/logout", response_model=StatusResponse)
@limiter.limit(SESSION_RATE_LIMIT)
def logout_endpoint(request: Request, authorization: str | None = Header(default=None)):
    auth.logout(authorization)
    return {"ok": True}


@router.get("/me", response_model=UserResponse)
@limiter.limit(SESSION_RATE_LIMIT)
def me_endpoint(request: Request, authorization: str | None = Header(default=None)):
    return auth.public_user(auth.require_user(authorization))


@router.delete("/me", response_model=StatusResponse)
@limiter.limit(AUTH_RATE_LIMIT)
def delete_me_endpoint(request: Request, authorization: str | None = Header(default=None)):
    auth.delete_account(authorization)
    return {"ok": True}


@router.post("/link-device", response_model=LinkDeviceResponse)
@limiter.limit(SESSION_RATE_LIMIT)
def link_device_endpoint(
    request: Request,
    authorization: str | None = Header(default=None),
    x_device_id: str = Header(..., alias="X-Device-Id"),
    x_device_token: str = Header(..., alias="X-Device-Token"),
):
    return {"linked_sessions": auth.link_device(authorization, x_device_id, x_device_token)}


@router.get("/me/route-sessions", response_model=list[RouteSessionResponse])
@limiter.limit(SESSION_RATE_LIMIT)
def my_route_sessions_endpoint(
    request: Request,
    limit: int = DEFAULT_SESSION_PAGE_SIZE,
    offset: int = 0,
    authorization: str | None = Header(default=None),
):
    user = auth.require_user(authorization)
    return route_session_repository.get_route_sessions_for_user(
        user_id=user["id"],
        limit=max(1, min(limit, MAX_SESSION_PAGE_SIZE)),
        offset=max(0, offset),
    )
