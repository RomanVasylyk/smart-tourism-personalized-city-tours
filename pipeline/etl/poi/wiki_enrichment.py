from __future__ import annotations

import json
import os
import re
import time
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
        if not force and has_text(poi.get("short_description")):
            return False

        page_ref = wikipedia_ref_from_poi(poi)
        if page_ref is None and has_text(poi.get("wikidata_id")):
            page_ref = self.resolve_wikipedia_page_from_wikidata(str(poi["wikidata_id"]))

        summary = self.fetch_wikipedia_summary(page_ref) if page_ref is not None else None
        description = clean_description(summary.extract if summary else None, max_description_chars)
        if not description and has_text(poi.get("wikidata_id")):
            description = clean_description(
                self.fetch_wikidata_description(str(poi["wikidata_id"])),
                max_description_chars,
            )

        changed = False
        if description and description != poi.get("short_description"):
            poi["short_description"] = description
            changed = True

        if page_ref is not None:
            if poi.get("wikipedia_title") != page_ref.title:
                poi["wikipedia_title"] = page_ref.title
                changed = True
            page_url = summary.page_url if summary else wikipedia_url(page_ref)
            if page_url and poi.get("wikipedia_url") != page_url:
                poi["wikipedia_url"] = page_url
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


def load_cache(cache_path: Path) -> dict[str, Any]:
    if not cache_path.exists():
        return {}
    try:
        payload = json.loads(cache_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}
    return payload if isinstance(payload, dict) else {}
