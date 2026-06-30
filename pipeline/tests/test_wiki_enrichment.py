from pathlib import Path

from etl.poi.wiki_enrichment import (
    WikiEnrichmentClient,
    WikiPageRef,
    clean_description,
    wikipedia_ref_from_url,
)


def test_clean_description_normalizes_whitespace_and_truncates():
    description = clean_description("  First line.\nSecond   line with many words.  ", max_chars=24)

    assert description == "First line. Second line..."


def test_wikipedia_ref_from_url_extracts_language_and_title():
    ref = wikipedia_ref_from_url("https://sk.wikipedia.org/wiki/Nitriansky_hrad")

    assert ref == WikiPageRef(lang="sk", title="Nitriansky hrad")


def test_enrich_poi_uses_wikidata_sitelink_and_wikipedia_summary(tmp_path: Path):
    cache_path = tmp_path / "wiki_summaries.json"
    client = WikiEnrichmentClient(
        cache_path=cache_path,
        languages=("sk", "en"),
        request_delay_seconds=0,
    )
    client.cache = {
        "wikidata-entity:Q123": {
            "entities": {
                "Q123": {
                    "sitelinks": {"skwiki": {"title": "Nitriansky hrad"}},
                    "descriptions": {"sk": {"value": "hrad na Slovensku"}},
                }
            }
        },
        "wikipedia-summary:sk:Nitriansky hrad": {
            "extract": "Nitriansky hrad je historický hradný komplex v Nitre.",
            "content_urls": {
                "desktop": {
                    "page": "https://sk.wikipedia.org/wiki/Nitriansky_hrad",
                }
            },
        },
    }
    poi = {
        "name": "Nitriansky hrad",
        "wikidata_id": "Q123",
        "wikipedia_title": None,
        "wikipedia_url": None,
        "short_description": None,
    }

    changed = client.enrich_poi(poi)

    assert changed is True
    assert poi["wikipedia_title"] == "Nitriansky hrad"
    assert poi["wikipedia_url"] == "https://sk.wikipedia.org/wiki/Nitriansky_hrad"
    assert poi["short_description"] == "Nitriansky hrad je historický hradný komplex v Nitre."


def test_enrich_poi_falls_back_to_wikidata_description(tmp_path: Path):
    client = WikiEnrichmentClient(
        cache_path=tmp_path / "wiki_summaries.json",
        languages=("sk", "en"),
        request_delay_seconds=0,
    )
    client.cache = {
        "wikidata-entity:Q456": {
            "entities": {
                "Q456": {
                    "sitelinks": {},
                    "descriptions": {"en": {"value": "public park in the city"}},
                }
            }
        }
    }
    poi = {"wikidata_id": "Q456", "short_description": None}

    changed = client.enrich_poi(poi)

    assert changed is True
    assert poi["short_description"] == "public park in the city"


def test_cached_json_reuses_cached_null_payload(tmp_path: Path):
    client = WikiEnrichmentClient(cache_path=tmp_path / "wiki_summaries.json")
    client.cache = {"missing-page": None}

    payload = client.cached_json(
        "missing-page",
        lambda: {"unexpected": True},
    )

    assert payload is None
