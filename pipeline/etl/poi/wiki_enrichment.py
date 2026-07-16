from __future__ import annotations

import json
import os
import re
import time
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import quote, unquote, urlparse

import requests

DEFAULT_LANGUAGES = ("sk", "en", "cs", "de", "pl", "hu")
DEFAULT_TIMEOUT_SECONDS = 12
DEFAULT_MAX_DESCRIPTION_CHARS = 420
DEFAULT_REQUEST_DELAY_SECONDS = 0.15
DEFAULT_USER_AGENT = "smart-tourism-starter/0.1 " "(local diploma project; Wikimedia summary enrichment)"
GEOSEARCH_LANG_LIMIT = 2
GEOSEARCH_RADIUS_METERS = 500
NAME_MATCH_THRESHOLD = 0.6


@dataclass(frozen=True)
class WikiPageRef:
    lang: str
    title: str


@dataclass(frozen=True)
class WikiSummary:
    extract: str | None
    page_url: str | None


class WikiEnrichmentClient:
    def __init__(
        self,
        *,
        cache_path: Path,
        languages: tuple[str, ...] = DEFAULT_LANGUAGES,
        timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
        request_delay_seconds: float = DEFAULT_REQUEST_DELAY_SECONDS,
        user_agent: str | None = None,
    ) -> None:
        self.cache_path = cache_path
        self.languages = languages
        self.timeout_seconds = timeout_seconds
        self.request_delay_seconds = request_delay_seconds
        self.session = requests.Session()
        self.session.headers.update(
            {"User-Agent": user_agent or os.getenv("WIKIMEDIA_USER_AGENT") or DEFAULT_USER_AGENT}
        )
        self.cache: dict[str, Any] = load_cache(cache_path)

    def save_cache(self) -> None:
        self.cache_path.parent.mkdir(parents=True, exist_ok=True)
        self.cache_path.write_text(
            json.dumps(self.cache, ensure_ascii=False, indent=2, sort_keys=True),
            encoding="utf-8",
        )

    def enrich_poi(
        self,
        poi: dict[str, Any],
        *,
        force: bool = False,
        max_description_chars: int = DEFAULT_MAX_DESCRIPTION_CHARS,
    ) -> bool:
        needs_description = force or not has_text(poi.get("short_description"))
        needs_page = needs_description or not has_text(poi.get("wikipedia_url"))
        needs_image = not has_text(poi.get("image_url")) and has_text(poi.get("wikidata_id"))
        if not (needs_description or needs_page or needs_image):
            return False

        page_ref = wikipedia_ref_from_poi(poi)
        if page_ref is None and has_text(poi.get("wikidata_id")):
            page_ref = self.resolve_wikipedia_page_from_wikidata(str(poi["wikidata_id"]))
        if page_ref is None and needs_page:
            lat, lon = poi.get("lat"), poi.get("lon")
            if isinstance(lat, (int, float)) and isinstance(lon, (int, float)) and has_text(poi.get("name")):
                page_ref = self.resolve_wikipedia_page_by_geosearch(float(lat), float(lon), str(poi["name"]))

        changed = False

        if needs_description:
            summary = self.fetch_wikipedia_summary(page_ref) if page_ref is not None else None
            description = clean_description(summary.extract if summary else None, max_description_chars)
            if not description and has_text(poi.get("wikidata_id")):
                description = clean_description(
                    self.fetch_wikidata_description(str(poi["wikidata_id"])),
                    max_description_chars,
                )
            if not description and has_text(poi.get("wikidata_id")):
                description = clean_description(
                    self.synthesize_from_wikidata(str(poi["wikidata_id"])),
                    max_description_chars,
                )
            if description and description != poi.get("short_description"):
                poi["short_description"] = description
                changed = True

        if page_ref is not None:
            if poi.get("wikipedia_title") != page_ref.title:
                poi["wikipedia_title"] = page_ref.title
                changed = True
            page_url = wikipedia_url(page_ref)
            if page_url and poi.get("wikipedia_url") != page_url:
                poi["wikipedia_url"] = page_url
                changed = True

        if needs_image and has_text(poi.get("wikidata_id")):
            image_url = self.wikidata_image_url(str(poi["wikidata_id"]))
            if image_url and image_url != poi.get("image_url"):
                poi["image_url"] = image_url
                changed = True

        return changed

    def fetch_wikipedia_summary(self, page_ref: WikiPageRef) -> WikiSummary | None:
        cache_key = f"wikipedia-summary:{page_ref.lang}:{page_ref.title}"
        payload = self.cached_json(cache_key, lambda: self.request_wikipedia_summary(page_ref))
        if not isinstance(payload, dict):
            return None

        extract = payload.get("extract")
        content_urls = payload.get("content_urls") or {}
        desktop_urls = content_urls.get("desktop") or {}
        page_url = desktop_urls.get("page") or wikipedia_url(page_ref)
        return WikiSummary(
            extract=extract if isinstance(extract, str) else None,
            page_url=page_url if isinstance(page_url, str) else None,
        )

    def request_wikipedia_summary(self, page_ref: WikiPageRef) -> dict[str, Any] | None:
        encoded_title = quote(page_ref.title.replace(" ", "_"), safe="")
        url = f"https://{page_ref.lang}.wikipedia.org/api/rest_v1/page/summary/{encoded_title}"
        return self.get_json(url)

    def resolve_wikipedia_page_from_wikidata(self, qid: str) -> WikiPageRef | None:
        entity = self.fetch_wikidata_entity(qid)
        if not entity:
            return None

        sitelinks = entity.get("sitelinks") or {}
        for lang in self.languages:
            site_key = f"{lang}wiki"
            site = sitelinks.get(site_key)
            if isinstance(site, dict) and has_text(site.get("title")):
                return WikiPageRef(lang=lang, title=str(site["title"]))
        return None

    def fetch_wikidata_description(self, qid: str) -> str | None:
        entity = self.fetch_wikidata_entity(qid)
        if not entity:
            return None

        descriptions = entity.get("descriptions") or {}
        for lang in self.languages:
            description = descriptions.get(lang)
            if isinstance(description, dict) and has_text(description.get("value")):
                return str(description["value"])
        return None

    def fetch_wikidata_entity(self, qid: str) -> dict[str, Any] | None:
        normalized_qid = qid.strip().upper()
        if not re.fullmatch(r"Q\d+", normalized_qid):
            return None

        cache_key = f"wikidata-entity:{normalized_qid}"
        payload = self.cached_json(
            cache_key,
            lambda: self.get_json(f"https://www.wikidata.org/wiki/Special:EntityData/{normalized_qid}.json"),
        )
        if not isinstance(payload, dict):
            return None
        entity = (payload.get("entities") or {}).get(normalized_qid)
        return entity if isinstance(entity, dict) else None

    def resolve_wikipedia_page_by_geosearch(self, lat: float, lon: float, name: str) -> WikiPageRef | None:
        target = normalize_name(name)
        if not target:
            return None
        best: tuple[float, WikiPageRef] | None = None
        for lang in self.languages[:GEOSEARCH_LANG_LIMIT]:
            for candidate in self.fetch_geosearch(lang, lat, lon):
                title = candidate.get("title")
                if not has_text(title):
                    continue
                score = name_match_score(target, normalize_name(str(title)))
                if score >= NAME_MATCH_THRESHOLD and (best is None or score > best[0]):
                    best = (score, WikiPageRef(lang=lang, title=str(title)))
        return best[1] if best is not None else None

    def fetch_geosearch(self, lang: str, lat: float, lon: float) -> list[dict[str, Any]]:
        cache_key = f"wikipedia-geosearch:{lang}:{lat:.5f}:{lon:.5f}"
        url = (
            f"https://{lang}.wikipedia.org/w/api.php?action=query&list=geosearch"
            f"&gscoord={lat:.6f}%7C{lon:.6f}&gsradius={GEOSEARCH_RADIUS_METERS}&gslimit=20&format=json"
        )
        payload = self.cached_json(cache_key, lambda: self.get_json(url))
        if not isinstance(payload, dict):
            return []
        results = (payload.get("query") or {}).get("geosearch")
        return [item for item in results if isinstance(item, dict)] if isinstance(results, list) else []

    def synthesize_from_wikidata(self, qid: str) -> str | None:
        entity = self.fetch_wikidata_entity(qid)
        if not entity:
            return None
        type_qid = first_claim_entity_id(entity, "P31")
        type_label = self.wikidata_label(type_qid) if type_qid else None
        year = first_claim_year(entity, "P571")
        if type_label and year:
            return f"{type_label[:1].upper()}{type_label[1:]} (z roku {year})."
        if type_label:
            return f"{type_label[:1].upper()}{type_label[1:]}."
        return None

    def wikidata_label(self, qid: str | None) -> str | None:
        if not qid:
            return None
        entity = self.fetch_wikidata_entity(qid)
        if not entity:
            return None
        labels = entity.get("labels") or {}
        for lang in self.languages:
            label = labels.get(lang)
            if isinstance(label, dict) and has_text(label.get("value")):
                return str(label["value"])
        return None

    def wikidata_image_url(self, qid: str) -> str | None:
        entity = self.fetch_wikidata_entity(qid)
        if not entity:
            return None
        filename = first_claim_string(entity, "P18")
        return commons_thumb_url(filename) if filename else None

    def cached_json(self, cache_key: str, fetch: Any) -> Any:
        if cache_key in self.cache:
            return self.cache[cache_key]

        payload = fetch()
        self.cache[cache_key] = payload
        return payload

    def get_json(self, url: str) -> dict[str, Any] | None:
        if self.request_delay_seconds > 0:
            time.sleep(self.request_delay_seconds)

        try:
            response = self.session.get(url, timeout=self.timeout_seconds)
            if response.status_code == 404:
                return None
            response.raise_for_status()
            payload = response.json()
        except (requests.RequestException, ValueError):
            return None
        return payload if isinstance(payload, dict) else None


def enrich_pois(
    pois: list[dict[str, Any]],
    *,
    cache_path: Path,
    languages: tuple[str, ...] = DEFAULT_LANGUAGES,
    force: bool = False,
    max_description_chars: int = DEFAULT_MAX_DESCRIPTION_CHARS,
) -> int:
    client = WikiEnrichmentClient(cache_path=cache_path, languages=languages)
    changed_count = 0
    for poi in pois:
        if client.enrich_poi(
            poi,
            force=force,
            max_description_chars=max_description_chars,
        ):
            changed_count += 1
    client.save_cache()
    return changed_count


def wikipedia_ref_from_poi(poi: dict[str, Any]) -> WikiPageRef | None:
    ref_from_url = wikipedia_ref_from_url(poi.get("wikipedia_url"))
    if ref_from_url is not None:
        return ref_from_url

    title = poi.get("wikipedia_title")
    if has_text(title):
        return WikiPageRef(lang=DEFAULT_LANGUAGES[0], title=str(title))
    return None


def wikipedia_ref_from_url(url: Any) -> WikiPageRef | None:
    if not has_text(url):
        return None

    parsed = urlparse(str(url))
    host_parts = parsed.netloc.split(".")
    if len(host_parts) < 3 or host_parts[1] != "wikipedia":
        return None
    if not parsed.path.startswith("/wiki/"):
        return None

    title = unquote(parsed.path.removeprefix("/wiki/")).replace("_", " ")
    if not title:
        return None
    return WikiPageRef(lang=host_parts[0], title=title)


def wikipedia_url(page_ref: WikiPageRef) -> str:
    encoded_title = quote(page_ref.title.replace(" ", "_"), safe="/:")
    return f"https://{page_ref.lang}.wikipedia.org/wiki/{encoded_title}"


def clean_description(value: str | None, max_chars: int = DEFAULT_MAX_DESCRIPTION_CHARS) -> str | None:
    if not has_text(value):
        return None

    normalized = re.sub(r"\s+", " ", str(value)).strip()
    if not normalized:
        return None
    if len(normalized) <= max_chars:
        return normalized

    truncated = normalized[: max_chars + 1].rsplit(" ", 1)[0].rstrip(" ,;:")
    return f"{truncated}..." if truncated else normalized[:max_chars].rstrip()


def has_text(value: Any) -> bool:
    return isinstance(value, str) and bool(value.strip())


def normalize_name(value: str | None) -> str:
    if not has_text(value):
        return ""
    decomposed = unicodedata.normalize("NFD", str(value))
    ascii_value = "".join(ch for ch in decomposed if unicodedata.category(ch) != "Mn")
    ascii_value = re.sub(r"\(.*?\)", " ", ascii_value)
    ascii_value = re.sub(r"[^a-z0-9 ]+", " ", ascii_value.lower())
    return re.sub(r"\s+", " ", ascii_value).strip()


def name_match_score(a: str, b: str) -> float:
    if not a or not b:
        return 0.0
    if a == b or a in b or b in a:
        return 1.0
    tokens_a = set(a.split())
    tokens_b = set(b.split())
    if not tokens_a or not tokens_b:
        return 0.0
    return len(tokens_a & tokens_b) / len(tokens_a | tokens_b)


def commons_thumb_url(filename: str | None, width: int = 640) -> str | None:
    if not has_text(filename):
        return None
    name = str(filename).removeprefix("File:").strip().replace(" ", "_")
    return f"https://commons.wikimedia.org/wiki/Special:FilePath/{quote(name)}?width={width}"


def first_claim_entity_id(entity: dict[str, Any], prop: str) -> str | None:
    for claim in (entity.get("claims") or {}).get(prop) or []:
        value = ((claim.get("mainsnak") or {}).get("datavalue") or {}).get("value")
        if isinstance(value, dict) and has_text(value.get("id")):
            return str(value["id"])
    return None


def first_claim_string(entity: dict[str, Any], prop: str) -> str | None:
    for claim in (entity.get("claims") or {}).get(prop) or []:
        value = ((claim.get("mainsnak") or {}).get("datavalue") or {}).get("value")
        if isinstance(value, str) and has_text(value):
            return value
    return None


def first_claim_year(entity: dict[str, Any], prop: str) -> str | None:
    for claim in (entity.get("claims") or {}).get(prop) or []:
        value = ((claim.get("mainsnak") or {}).get("datavalue") or {}).get("value")
        if isinstance(value, dict) and has_text(value.get("time")):
            match = re.match(r"^[+-](\d+)-", str(value["time"]))
            if match:
                return str(int(match.group(1)))
    return None


def load_cache(cache_path: Path) -> dict[str, Any]:
    if not cache_path.exists():
        return {}
    try:
        payload = json.loads(cache_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}
