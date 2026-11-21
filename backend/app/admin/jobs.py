from __future__ import annotations

import os
import subprocess
import sys
from dataclasses import dataclass
from threading import Lock, Thread

from app.admin import pipeline as data_pipeline
from app.db.database import get_connection

MAX_LOG_LINES = 4000
INTERRUPTED = "Úloha bola prerušená reštartom servera."


@dataclass(frozen=True)
class JobKind:
    label: str
    steps: tuple[tuple[str, ...], ...]
    downloads: bool


JOB_KINDS = {
    "pois_import": JobKind(
        "Stiahnutie bodov záujmu z OpenStreetMap",
        (
            ("scripts/import_osm.py",),
            ("scripts/normalize_categories.py",),
            ("scripts/enrich_pois.py",),
            ("scripts/review_pois.py",),
        ),
        True,
    ),
    "pois_process": JobKind(
        "Spracovanie bodov záujmu",
        (("scripts/normalize_categories.py",), ("scripts/enrich_pois.py",), ("scripts/review_pois.py",)),
        False,
    ),
    "transport_import": JobKind(
        "Stiahnutie cestovných poriadkov",
        (
            ("scripts/import_transport.py",),
            ("scripts/normalize_transport.py",),
            ("scripts/validate_transport_graph.py", "--write"),
        ),
        True,
    ),
    "transport_process": JobKind(
        "Spracovanie cestovných poriadkov",
        (("scripts/normalize_transport.py",), ("scripts/validate_transport_graph.py", "--write")),
        False,
    ),
    "curated_routes": JobKind("Načítanie kurátorských okruhov", (("scripts/load_curated_routes.py",),), False),
}


class JobBusyError(RuntimeError):
    pass


_lock = Lock()
_running: dict[int, list[str]] = {}


def commands(kind: str, slug: str) -> list[list[str]]:
    return [[sys.executable, "-u", step[0], slug, *step[1:]] for step in JOB_KINDS[kind].steps]


def kind_labels() -> dict[str, dict]:
    return {key: {"label": kind.label, "downloads": kind.downloads} for key, kind in JOB_KINDS.items()}


def insert_job(kind: str, slug: str, started_by: str) -> int:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO pipeline_jobs (city_slug, kind, status, started_by) VALUES (%s, %s, 'running', %s) RETURNING id",
                (slug, kind, started_by),
            )
            job_id = cur.fetchone()["id"]
        conn.commit()
    return job_id


def finish_job(job_id: int, exit_code: int, lines: list[str]) -> None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE pipeline_jobs
                SET status = %s, exit_code = %s, finished_at = NOW(), log = %s
                WHERE id = %s
                """,
                ("succeeded" if exit_code == 0 else "failed", exit_code, "\n".join(lines), job_id),
            )
        conn.commit()


def run_job(job_id: int, kind: str, slug: str) -> None:
    lines = _running[job_id]
    exit_code = 0
    try:
        for command in commands(kind, slug):
            lines.append("$ python " + " ".join(command[2:]))
            process = subprocess.Popen(
                command,
                cwd=data_pipeline.PIPELINE_ROOT,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                env={**os.environ, "PYTHONUNBUFFERED": "1"},
            )
            for line in process.stdout:
                lines.append(line.rstrip("\n"))
                if len(lines) > MAX_LOG_LINES:
                    del lines[: len(lines) - MAX_LOG_LINES]
            exit_code = process.wait()
            if exit_code != 0:
                lines.append(f"Krok skončil s chybou (kód {exit_code}), ďalšie kroky sa nespustili.")
                break
    except OSError as exc:
        lines.append(f"Úlohu sa nepodarilo spustiť: {exc}")
        exit_code = -1
    finally:
        try:
            finish_job(job_id, exit_code, lines)
        finally:
            data_pipeline.clear_caches()
            with _lock:
                _running.pop(job_id, None)


def start_job(kind: str, slug: str, started_by: str) -> int:
    if kind not in JOB_KINDS:
        raise KeyError(kind)
    with _lock:
        if _running:
            raise JobBusyError("Práve beží iná úloha. Počkajte, kým sa skončí.")
        job_id = insert_job(kind, slug, started_by)
        _running[job_id] = []
    Thread(target=run_job, args=(job_id, kind, slug), daemon=True).start()
    return job_id


def mark_interrupted() -> None:
    with _lock:
        active = list(_running)
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                UPDATE pipeline_jobs
                SET status = 'failed', finished_at = NOW(), log = log || %s
                WHERE status = 'running' AND NOT (id = ANY(%s))
                """,
                (f"\n{INTERRUPTED}", active),
            )
        conn.commit()


def list_jobs(limit: int = 12) -> list[dict]:
    mark_interrupted()
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, city_slug, kind, status, started_by, started_at, finished_at, exit_code
                FROM pipeline_jobs ORDER BY id DESC LIMIT %s
                """,
                (limit,),
            )
            rows = cur.fetchall()
    return [{**row, "label": JOB_KINDS[row["kind"]].label if row["kind"] in JOB_KINDS else row["kind"]} for row in rows]


def job_detail(job_id: int) -> dict | None:
    with get_connection() as conn:
        with conn.cursor() as cur:
            cur.execute(
                """
                SELECT id, city_slug, kind, status, started_by, started_at, finished_at, exit_code, log
                FROM pipeline_jobs WHERE id = %s
                """,
                (job_id,),
            )
            row = cur.fetchone()
    if row is None:
        return None
    with _lock:
        live = list(_running.get(job_id, [])) if job_id in _running else None
    if live is not None:
        row["log"] = "\n".join(live)
    row["label"] = JOB_KINDS[row["kind"]].label if row["kind"] in JOB_KINDS else row["kind"]
    return row
