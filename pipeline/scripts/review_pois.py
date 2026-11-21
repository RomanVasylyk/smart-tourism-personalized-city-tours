from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from etl.poi.review import review_pois, snapshot_of
from utils.approval import load_approval, write_approval
from utils.cities import load_city, load_city_profiles

EXAMPLES_PER_CODE = 5


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Check processed POIs, show the changes since the last approval and let a data administrator approve them.",
    )
    parser.add_argument("city", nargs="?", default="nitra", help="City slug from pipeline/config/cities.yaml")
    parser.add_argument("--all-cities", action="store_true", help="Review every configured city.")
    parser.add_argument(
        "--approve", action="store_true", help="Approve the reviewed data for loading into the database."
    )
    parser.add_argument("--reviewer", help="Name of the data administrator who approves the data.")
    parser.add_argument(
        "--allow-errors",
        action="store_true",
        help="Approve even though the review found errors.",
    )
    return parser.parse_args()


def processed_file(city: dict) -> Path:
    return ROOT / "data" / "processed" / f"{city['slug']}_pois_normalized.json"


def print_issues(title: str, issues: list[dict]) -> None:
    grouped: dict[str, list[dict]] = defaultdict(list)
    for item in issues:
        grouped[item["code"]].append(item)
    for code, items in sorted(grouped.items()):
        print(f"  {title} {code}: {len(items)}")
        for item in items[:EXAMPLES_PER_CODE]:
            print(f"    - {item['key']} {item['name'] or '(bez názvu)'}: {item['message']}")


def print_changes(changes: dict | None) -> None:
    if changes is None:
        print("  changes: no previous approval, all POIs are new")
        return
    print(
        f"  changes since last approval: +{len(changes['added'])} added, "
        f"-{len(changes['removed'])} removed, ~{len(changes['changed'])} changed"
    )
    for label, items in (("added", changes["added"]), ("removed", changes["removed"]), ("changed", changes["changed"])):
        for item in items[:EXAMPLES_PER_CODE]:
            fields = f" ({', '.join(item['fields'])})" if "fields" in item else ""
            print(f"    {label} {item['key']} {item['name']}{fields}")


class ReviewBlockedError(ValueError):
    pass


def review_file(city: dict) -> Path:
    return processed_file(city).with_name(f"{city['slug']}_pois_review.json")


def review_city_data(city: dict) -> tuple[Path, list[dict], dict]:
    data_file = processed_file(city)
    if not data_file.exists():
        raise FileNotFoundError(f"Processed POI file not found: {data_file}")
    pois = json.loads(data_file.read_text(encoding="utf-8"))
    previous = load_approval(data_file)
    report = review_pois(pois, city, (previous or {}).get("snapshot"))
    review_file(city).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    return data_file, pois, report


def approve_reviewed(
    data_file: Path, pois: list[dict], report: dict, reviewer: str, allow_errors: bool = False
) -> Path:
    if report["errors"] and not allow_errors:
        raise ReviewBlockedError(f"The review found {len(report['errors'])} errors.")
    return write_approval(data_file, reviewer, report["summary"], snapshot_of(pois))


def review_city(city: dict, args: argparse.Namespace) -> bool:
    try:
        data_file, pois, report = review_city_data(city)
    except FileNotFoundError as exc:
        print(f"{city['slug']}: {exc}")
        return False
    report_file = review_file(city)

    summary = report["summary"]
    print(
        f"{city['slug']}: {summary['active']} active POIs, {summary['error_count']} errors, "
        f"{summary['warning_count']} warnings, categories {summary['by_category']}"
    )
    print_issues("error", report["errors"])
    print_issues("warning", report["warnings"])
    print_changes(report["changes"])
    print(f"  full report: {report_file}")

    if not args.approve:
        return True
    if not args.reviewer:
        print("  not approved: pass --reviewer with the name of the data administrator")
        return False
    try:
        approval_file = approve_reviewed(data_file, pois, report, args.reviewer, args.allow_errors)
    except ReviewBlockedError:
        print("  not approved: fix the errors first or pass --allow-errors")
        return False
    print(f"  approved by {args.reviewer}: {approval_file}")
    return True


def main() -> None:
    args = parse_args()
    cities = load_city_profiles() if args.all_cities else [load_city(args.city)]
    results = [review_city(city, args) for city in cities]
    if not all(results):
        sys.exit(1)


if __name__ == "__main__":
    main()
