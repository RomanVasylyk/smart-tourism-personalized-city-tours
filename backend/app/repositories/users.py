from __future__ import annotations

from datetime import datetime
from uuid import UUID

from app.db.database import get_connection
from psycopg import errors

USER_COLUMNS = "u.id, u.email, u.display_name, u.is_admin, u.is_active, u.created_at, u.last_login_at"


def insert_user(email: str, password_hash: str, display_name: str | None, is_admin: bool = False) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            try:
                cur.execute(
                    f"""
                    INSERT INTO users AS u (email, password_hash, display_name, is_admin)
                    VALUES (%s, %s, %s, %s)
                    RETURNING {USER_COLUMNS}
                    """,
                    (email, password_hash, display_name, is_admin),
                )
            except errors.UniqueViolation:
                conn.rollback()
                return None
            row = cur.fetchone()
            conn.commit()
            return row


def find_user_with_password(email: str) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"SELECT {USER_COLUMNS}, u.password_hash FROM users u WHERE lower(u.email) = lower(%s)",
                (email,),
            )
            return cur.fetchone()


def find_user_by_id(user_id: int) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(f"SELECT {USER_COLUMNS} FROM users u WHERE u.id = %s", (user_id,))
            return cur.fetchone()


def set_password_hash(user_id: int, password_hash: str) -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("UPDATE users SET password_hash = %s WHERE id = %s", (password_hash, user_id))
            cur.execute("DELETE FROM user_tokens WHERE user_id = %s", (user_id,))
        conn.commit()


def record_login(user_id: int) -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("UPDATE users SET last_login_at = NOW() WHERE id = %s", (user_id,))
        conn.commit()


def insert_token(user_id: int, token_hash: str, expires_at: datetime) -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO user_tokens (user_id, token_hash, expires_at) VALUES (%s, %s, %s)",
                (user_id, token_hash, expires_at),
            )
        conn.commit()


def find_user_by_token_hash(token_hash: str) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                f"""
                UPDATE user_tokens t
                SET last_used_at = NOW()
                FROM users u
                WHERE t.token_hash = %s
                  AND t.expires_at > NOW()
                  AND u.id = t.user_id
                  AND u.is_active = TRUE
                RETURNING {USER_COLUMNS}
                """,
                (token_hash,),
            )
            row = cur.fetchone()
        conn.commit()
        return row


def delete_token(token_hash: str) -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM user_tokens WHERE token_hash = %s", (token_hash,))
        conn.commit()


def delete_user(user_id: int) -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute("DELETE FROM users WHERE id = %s", (user_id,))
        conn.commit()


def link_device_sessions(user_id: int, device_id: str, device_token_hash: str) -> int:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE route_sessions
                SET user_id = %s
                WHERE device_id = %s
                  AND device_token_hash = %s
                  AND (user_id IS NULL OR user_id = %s)
                """,
                (user_id, device_id, device_token_hash, user_id),
            )
            count = cur.rowcount
        conn.commit()
        return count


def assign_session_user(session_id: UUID, device_id: str, device_token_hash: str, user_id: int) -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE route_sessions
                SET user_id = %s
                WHERE id = %s
                  AND device_id = %s
                  AND device_token_hash = %s
                  AND user_id IS NULL
                """,
                (user_id, session_id, device_id, device_token_hash),
            )
        conn.commit()
