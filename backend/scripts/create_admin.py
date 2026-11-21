from __future__ import annotations

import argparse
import getpass
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.db.database import get_connection
from app.services.auth import hash_password, normalize_email, validate_password
from fastapi import HTTPException


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Create an administrator account or grant administrator rights.")
    parser.add_argument("email", help="E-mail of the administrator.")
    parser.add_argument("--name", default=None, help="Display name.")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    try:
        email = normalize_email(args.email)
        password = getpass.getpass("Password: ")
        if password != getpass.getpass("Repeat password: "):
            raise SystemExit("Passwords do not match.")
        validate_password(password)
    except HTTPException as exc:
        raise SystemExit(exc.detail) from exc

    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                INSERT INTO users (email, password_hash, display_name, is_admin, is_active)
                VALUES (%s, %s, %s, TRUE, TRUE)
                ON CONFLICT ((lower(email))) DO UPDATE
                SET password_hash = EXCLUDED.password_hash,
                    is_admin = TRUE,
                    is_active = TRUE,
                    display_name = COALESCE(EXCLUDED.display_name, users.display_name)
                RETURNING id
                """,
                (email, hash_password(password), args.name),
            )
            user_id = cur.fetchone()["id"]
            cur.execute("DELETE FROM user_tokens WHERE user_id = %s", (user_id,))
        conn.commit()
    print(f"Administrator {email} is ready (user id {user_id}).")


if __name__ == "__main__":
    main()
