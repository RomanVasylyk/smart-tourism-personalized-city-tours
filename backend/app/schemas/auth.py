from datetime import datetime

from pydantic import BaseModel, Field


class RegisterRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=8, max_length=128)
    display_name: str | None = Field(default=None, max_length=80)


class LoginRequest(BaseModel):
    email: str = Field(min_length=3, max_length=254)
    password: str = Field(min_length=1, max_length=128)


class UserResponse(BaseModel):
    id: int
    email: str
    display_name: str | None = None
    is_admin: bool = False
    created_at: datetime | None = None


class AuthResponse(BaseModel):
    token: str
    user: UserResponse


class LinkDeviceResponse(BaseModel):
    linked_sessions: int


class StatusResponse(BaseModel):
    ok: bool = True
