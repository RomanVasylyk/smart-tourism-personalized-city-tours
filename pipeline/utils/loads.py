from __future__ import annotations

from pathlib import Path

from utils.approval import file_sha256


def record_load(conn, dataset: str, city_slug: str, data_file: Path, loaded_by: str | None) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            INSERT INTO data_loads (dataset, city_slug, file_name, sha256, loaded_by)
            VALUES (%s, %s, %s, %s, %s)
            """,
            (dataset, city_slug, data_file.name, file_sha256(data_file), loaded_by),
        )
