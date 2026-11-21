from __future__ import annotations

import base64
import hashlib
import hmac
import os
import re
import secrets
from datetime import UTC, datetime, timedelta
from uuid import UUID

from app.repositories import users as user_repository
from app.services.route_sessions import hash_authenticated_device_token, require_authenticated_device_id
from fastapi import HTTPException

SCRYPT_N = 2**14
SCRYPT_R = 8
SCRYPT_P = 1
SCRYPT_DKLEN = 64
TOKEN_LIFETIME = timedelta(days=30)
MIN_PASSWORD_LENGTH = 8
EMAIL_PATTERN = re.compile(r"^[^@\s]+@[^@\s]+\.[^@\s]+$")


def b64(value: bytes) -> str:
    return base64.b64encode(value).decode("ascii")


def hash_password(password: str) -> str:
    salt = os.urandom(16)
    digest = hashlib.scrypt(password.encode("utf-8"), salt=salt, n=SCRYPT_N, r=SCRYPT_R, p=SCRYPT_P, dklen=SCRYPT_DKLEN)
    return f"scrypt${SCRYPT_N}${SCRYPT_R}${SCRYPT_P}${b64(salt)}${b64(digest)}"


def verify_password(password: str, stored_hash: str) -> bool:
    try:
        algorithm, n, r, p, salt, digest = stored_hash.split("$")
        if algorithm != "scrypt":
            return False
        expected = base64.b64decode(digest)
        actual = hashlib.scrypt(
            password.encode("utf-8"),
            salt=base64.b64decode(salt),
            n=int(n),
            r=int(r),
            p=int(p),
            dklen=len(expected),
        )
    except (ValueError, TypeError):
        return False
    return hmac.compare_digest(actual, expected)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def normalize_email(email: str) -> str:
    normalized = email.strip().lower()
    if not EMAIL_PATTERN.match(normalized):
        raise HTTPException(status_code=422, detail="Invalid e-mail address.")
    return normalized


def validate_password(password: str) -> None:
    if len(password) < MIN_PASSWORD_LENGTH:
        raise HTTPException(status_code=422, detail=f"Password must have at least {MIN_PASSWORD_LENGTH} characters.")


def public_user(user: dict) -> dict:
    return {
        "id": user["id"],
        "email": user["email"],
        "display_name": user.get("display_name"),
        "is_admin": bool(user.get("is_admin")),
        "created_at": user.get("created_at"),
    }


def issue_token(user: dict, lifetime: timedelta = TOKEN_LIFETIME) -> str:
    token = secrets.token_urlsafe(32)
    user_repository.insert_token(user["id"], hash_token(token), datetime.now(UTC) + lifetime)
    return token


def register_user(email: str, password: str, display_name: str | None) -> dict:
    normalized_email = normalize_email(email)
    validate_password(password)
    name = (display_name or "").strip() or None
    user = user_repository.insert_user(normalized_email, hash_password(password), name)
    if user is None:
        raise HTTPException(status_code=409, detail="An account with this e-mail already exists.")
    user_repository.record_login(user["id"])
    return {"token": issue_token(user), "user": public_user(user)}


def authenticate(email: str, password: str) -> dict:
    user = user_repository.find_user_with_password(email.strip().lower())
    if user is None or not verify_password(password, user["password_hash"]):
        raise HTTPException(status_code=401, detail="Invalid e-mail or password.")
    if not user["is_active"]:
        raise HTTPException(status_code=403, detail="This account is disabled.")
    user_repository.record_login(user["id"])
    return user


def login_user(email: str, password: str) -> dict:
    user = authenticate(email, password)
    return {"token": issue_token(user), "user": public_user(user)}


def bearer_token(authorization: str | None) -> str | None:
    if not authorization:
        return None
    scheme, _, value = authorization.partition(" ")
    if scheme.lower() != "bearer" or not value.strip():
        return None
    return value.strip()


def user_for_token(token: str | None) -> dict | None:
    if not token:
        return None
    return user_repository.find_user_by_token_hash(hash_token(token))


def optional_user(authorization: str | None) -> dict | None:
    return user_for_token(bearer_token(authorization))


def require_user(authorization: str | None) -> dict:
    user = optional_user(authorization)
    if user is None:
        raise HTTPException(status_code=401, detail="Login required.")
    return user


def logout(authorization: str | None) -> None:
    token = bearer_token(authorization)
    if token:
        user_repository.delete_token(hash_token(token))


def delete_account(authorization: str | None) -> None:
    user = require_user(authorization)
    user_repository.delete_user(user["id"])


def link_device(authorization: str | None, device_id: str, device_token: str) -> int:
    user = require_user(authorization)
    return user_repository.link_device_sessions(
        user["id"],
        require_authenticated_device_id(device_id),
        hash_authenticated_device_token(device_token),
    )


def attach_session_to_user(authorization: str | None, session_id: UUID, device_id: str, device_token: str) -> None:
    user = optional_user(authorization)
    if user is None:
        return
    user_repository.assign_session_user(
        session_id,
        require_authenticated_device_id(device_id),
        hash_authenticated_device_token(device_token),
        user["id"],
    )
