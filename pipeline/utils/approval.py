from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path


class ApprovalError(RuntimeError):
    pass


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def approval_path(data_file: Path) -> Path:
    return data_file.with_name(f"{data_file.stem}_approval.json")


def load_approval(data_file: Path) -> dict | None:
    path = approval_path(data_file)
    if not path.exists():
        return None
    return json.loads(path.read_text(encoding="utf-8"))


def write_approval(data_file: Path, reviewer: str, summary: dict, snapshot: dict | None = None) -> Path:
    payload = {
        "file": data_file.name,
        "sha256": file_sha256(data_file),
        "reviewer": reviewer,
        "approved_at": datetime.now(UTC).isoformat(timespec="seconds"),
        "summary": summary,
    }
    if snapshot is not None:
        payload["snapshot"] = snapshot
    path = approval_path(data_file)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    return path


def require_approval(data_file: Path) -> dict:
    approval = load_approval(data_file)
    if approval is None:
        raise ApprovalError(f"{data_file.name} has not been approved by a data administrator.")
    if approval.get("sha256") != file_sha256(data_file):
        raise ApprovalError(f"{data_file.name} changed after it was approved on {approval.get('approved_at')}.")
    return approval
