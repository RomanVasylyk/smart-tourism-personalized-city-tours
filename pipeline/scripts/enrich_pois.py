from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from etl.poi.wiki_enrichment import DEFAULT_LANGUAGES, enrich_pois
from utils.cities import load_city, load_city_profiles


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Enrich normalized POIs with Wikipedia/Wikidata short summaries.",
    )
    parser.add_argument("city", nargs="?", default="nitra", help="City slug from pipeline/config/cities.yaml")
    parser.add_argument(
        "--all-cities",
        action="store_true",
        help="Enrich every city configured in pipeline/config/cities.yaml.",
    )
    parser.add_argument(
        "--force",
        action="store_true",
        help="Refresh descriptions even when short_description is already present.",
    )
    parser.add_argument(
        "--languages",
        default=",".join(DEFAULT_LANGUAGES),
        help="Comma-separated preferred Wikipedia/Wikidata languages.",
    )
    parser.add_argument(
        "--max-description-chars",
        type=int,
        default=420,
        help="Maximum characters stored in short_description.",
    )
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    cache_file = ROOT / "data" / "cache" / "wiki_summaries.json"
    languages = tuple(lang.strip() for lang in args.languages.split(",") if lang.strip())
    cities = load_city_profiles() if args.all_cities else [load_city(args.city)]

    for city in cities:
        slug = city["slug"]
        processed_file = ROOT / "data" / "processed" / f"{slug}_pois_normalized.json"
        pois = json.loads(processed_file.read_text(encoding="utf-8"))
        changed_count = enrich_pois(
            pois,
            cache_path=cache_file,
            languages=languages or DEFAULT_LANGUAGES,
            force=args.force,
            max_description_chars=args.max_description_chars,
        )
        processed_file.write_text(json.dumps(pois, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"Enriched {changed_count} POIs in {processed_file}")


if __name__ == "__main__":
    main()
