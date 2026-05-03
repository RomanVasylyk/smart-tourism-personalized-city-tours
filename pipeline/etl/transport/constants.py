from __future__ import annotations

import re
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
STOP_ALIAS_CONFIG_FILE = ROOT / "config" / "transport_stop_aliases.yaml"
VALIDITY_PATTERN = re.compile(r"Plati od\s+(\d{1,2}\.\d{1,2}\.\d{4})\s+do\s+(\d{1,2}\.\d{1,2}\.\d{4})", re.IGNORECASE)
LINE_NUMBER_PATTERN = re.compile(r"^\d{1,3}$")
TIME_TOKEN_PATTERN = re.compile(r"\bK\b|(?<!\w)(?:--?|x|X|…)(?!\w)|(?<!\d)(\d{1,2})\s?(\d{2})(?!\d)")
TIME_VALUE_PATTERN = re.compile(r"(?<!\d)(\d{1,2})\s?(\d{2})(?!\d)")
STOP_ROW_PATTERN = re.compile(r"^(?P<prefix>[wWxX]{0,2}[DASZ])\s+(?P<body>.+)$", re.IGNORECASE)
PLATFORM_SUFFIX_PATTERNS = (
    re.compile(r"\(\s*(?P<token>[A-Z]|\d{1,2}|[A-Z]\d|\d[A-Z])\s*\)\s*$"),
    re.compile(r"[,/-]\s*(?P<token>[A-Z]|\d{1,2}|[A-Z]\d|\d[A-Z])\s*$"),
    re.compile(r"\b(?:NAST|NASTUPISTE|NASTUPISTE|PLATF|PLATFORM|STANOVISTE)\.?\s*(?P<token>[A-Z0-9]{1,4})\s*$"),
    re.compile(r"\s+(?P<token>[A-Z])\s*$"),
)
METADATA_CONTAINS_MARKERS = (
    "PLATI OD",
    "PREPRAVU ZABEZPECUJE",
    "ZOZNAM ZASTAVOK",
    "VSETKY SPOJE",
    "S BEZBARIEROVYM PRISTUPOM",
    "ZASTAVKA JE LEN NA ZNAMENIE",
    "PRESTUP NA VLAK",
)
METADATA_EXACT_MARKERS = {
    "POKRACOVANIE",
    "OPACNY SMER",
    "OPACNY SMER - POKRACOVANIE",
}
FOOTNOTE_LINE_PATTERN = re.compile(
    r"^(?:\+|[a-z]{1,3}\s+(?:premava|nepremava|spoj)|[HDJpwx]\s+zastavka|p\s+spoj)\b",
    re.IGNORECASE,
)
STOP_NOTE_TAIL_PATTERN = re.compile(
    r"\s+\+\s+premava\b.*$|\s+[a-z]{1,3}\s+(?:premava|nepremava)\b.*$|\s+p\s+spoj\b.*$",
    re.IGNORECASE,
)
SERVICE_BUCKETS = {"workdays", "weekends_holidays", "all_days"}
FULL_KEY_FUZZY_CUTOFF = 0.88
BASE_KEY_FUZZY_CUTOFF = 0.92
STOP_NAME_ABBREVIATIONS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\bUL\.\b", re.IGNORECASE), "ULICA"),
    (re.compile(r"\bNAM\.\b", re.IGNORECASE), "NAMESTIE"),
    (re.compile(r"\bNABR\.\b", re.IGNORECASE), "NABREZIE"),
    (re.compile(r"\bSV\.\b", re.IGNORECASE), "SVATEHO"),
    (re.compile(r"\bZEL\.\s*ST\.\b", re.IGNORECASE), "ZELEZNICNA STANICA"),
    (re.compile(r"\bAUT\.\s*ST\.\b", re.IGNORECASE), "AUTOBUSOVA STANICA"),
)
LOCAL_CONNECTION_RULES: dict[str, tuple[dict[str, Any], ...]] = {
    "nitra": (
        {"from": "Šindolka , Dolnohorská", "to": "Šindolka", "action": "ignore_missing_edge"},
        {"from": "Priemyselný park , nadjazd", "to": "Priemyselný park VII", "action": "ignore_missing_edge"},
        {"from": "Priemyselný park VII", "to": "Priemyselný park , nadjazd", "action": "ignore_missing_edge"},
        {"from": "Kmeťova", "to": "Považská", "action": "estimated_edge_seconds", "seconds": 120.0},
        {"from": "Rybárska", "to": "Hollého", "action": "ignore_missing_edge"},
        {"from": "Rázcestie Železničná stanica", "to": "Železničná stanica Nitra", "action": "ignore_missing_edge"},
    ),
}
