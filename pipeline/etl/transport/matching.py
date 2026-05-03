from __future__ import annotations

import math
from difflib import get_close_matches

from .constants import BASE_KEY_FUZZY_CUTOFF, FULL_KEY_FUZZY_CUTOFF
from .text import build_stop_match_keys, normalize_stop_base_name, normalize_stop_name, split_stop_name_components

def build_osm_stop_index(stops: list[dict]) -> dict[str, list[dict]]:
    index: dict[str, list[dict]] = {}
    for stop in stops:
        for key in stop["match_keys"]:
            index.setdefault(key, []).append(stop)
    for candidates in index.values():
        candidates.sort(key=lambda item: (item["distance_to_center_meters"], item["osm_id"]))
    return index

def deduplicate_candidates(candidates: list[dict]) -> list[dict]:
    deduplicated_candidates: list[dict] = []
    seen_osm_ids: set[str] = set()
    for candidate in candidates:
        osm_id = candidate["osm_id"]
        if osm_id in seen_osm_ids:
            continue
        seen_osm_ids.add(osm_id)
        deduplicated_candidates.append(candidate)
    return deduplicated_candidates

def fuzzy_match_key(target_key: str, available_keys: list[str], cutoff: float) -> str | None:
    if not target_key or not available_keys:
        return None
    matches = get_close_matches(target_key, available_keys, n=1, cutoff=cutoff)
    return matches[0] if matches else None

def match_provider_stop_candidates(
    stop_name: str,
    osm_index: dict[str, list[dict]],
    stop_aliases: dict[str, str],
) -> tuple[list[dict], str | None]:
    base_name, platform_token = split_stop_name_components(stop_name, stop_aliases)
    full_key = normalize_stop_name(stop_name, stop_aliases)
    base_key = normalize_stop_base_name(base_name)
    provider_match_keys = sorted(
        build_stop_match_keys(stop_name, stop_aliases=stop_aliases),
        key=lambda key: (0 if " platform " in key else 1, -len(key), key),
    )
    base_match_keys = sorted(
        {key for key in provider_match_keys if " platform " not in key},
        key=lambda key: (-len(key), key),
    )

    for lookup_key in provider_match_keys:
        exact_candidates = deduplicate_candidates(osm_index.get(lookup_key, []))
        if exact_candidates:
            matched_by = "exact" if lookup_key == full_key else "exact_alt"
            if len(exact_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return exact_candidates, matched_by

    if platform_token:
        for lookup_key in base_match_keys or [base_key]:
            base_candidates = [
                candidate
                for candidate in deduplicate_candidates(osm_index.get(lookup_key, []))
                if candidate.get("platform_token") == platform_token
            ]
            if base_candidates:
                matched_by = "platform_ref" if len(base_candidates) == 1 else "platform_ref_multi"
                return base_candidates, matched_by

    for lookup_key in base_match_keys or [base_key]:
        exact_base_candidates = deduplicate_candidates(osm_index.get(lookup_key, []))
        if exact_base_candidates:
            matched_by = "base_exact" if lookup_key == base_key else "base_exact_alt"
            if len(exact_base_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return exact_base_candidates, matched_by

    all_keys = list(osm_index.keys())
    for lookup_key in provider_match_keys:
        fuzzy_full_key = fuzzy_match_key(lookup_key, all_keys, FULL_KEY_FUZZY_CUTOFF)
        if fuzzy_full_key is not None:
            fuzzy_candidates = deduplicate_candidates(osm_index[fuzzy_full_key])
            matched_by = "fuzzy" if lookup_key == full_key else "fuzzy_alt"
            if len(fuzzy_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return fuzzy_candidates, matched_by

    for lookup_key in base_match_keys or [base_key]:
        fuzzy_base_key = fuzzy_match_key(lookup_key, all_keys, BASE_KEY_FUZZY_CUTOFF)
        if fuzzy_base_key is not None:
            fuzzy_base_candidates = deduplicate_candidates(osm_index[fuzzy_base_key])
            if platform_token:
                filtered_candidates = [
                    candidate
                    for candidate in fuzzy_base_candidates
                    if candidate.get("platform_token") == platform_token
                ]
                if filtered_candidates:
                    matched_by = "fuzzy_platform" if len(filtered_candidates) == 1 else "fuzzy_platform_multi"
                    return filtered_candidates, matched_by
            matched_by = "fuzzy_base" if lookup_key == base_key else "fuzzy_base_alt"
            if len(fuzzy_base_candidates) > 1:
                matched_by = f"{matched_by}_multi"
            return fuzzy_base_candidates, matched_by

    return [], None

def transition_cost(previous_stop: dict, current_stop: dict) -> float:
    distance_cost = haversine_km(
        previous_stop["lat"],
        previous_stop["lon"],
        current_stop["lat"],
        current_stop["lon"],
    ) * 1_000
    if previous_stop["osm_id"] == current_stop["osm_id"]:
        distance_cost += 150
    return distance_cost

def choose_variant_stop_assignments(
    stop_names: list[str],
    osm_index: dict[str, list[dict]],
    stop_aliases: dict[str, str],
) -> list[tuple[int, dict, str]]:
    matched_entries = []
    for index, stop_name in enumerate(stop_names):
        candidates, matched_by = match_provider_stop_candidates(stop_name, osm_index, stop_aliases)
        if not candidates:
            continue
        matched_entries.append(
            {
                "index": index,
                "stop_name": stop_name,
                "matched_by": matched_by or "unknown",
                "candidates": candidates,
            }
        )

    if not matched_entries:
        return []
    if len(matched_entries) == 1:
        entry = matched_entries[0]
        return [(entry["index"], entry["candidates"][0], entry["matched_by"])]

    costs: list[list[float]] = []
    parents: list[list[int | None]] = []

    first_candidates = matched_entries[0]["candidates"]
    costs.append([0.0 for _ in first_candidates])
    parents.append([None for _ in first_candidates])

    for entry_index in range(1, len(matched_entries)):
        current_candidates = matched_entries[entry_index]["candidates"]
        previous_candidates = matched_entries[entry_index - 1]["candidates"]
        current_costs: list[float] = []
        current_parents: list[int | None] = []

        for current_candidate in current_candidates:
            best_cost = math.inf
            best_parent: int | None = None
            for previous_index, previous_candidate in enumerate(previous_candidates):
                cost = costs[entry_index - 1][previous_index] + transition_cost(previous_candidate, current_candidate)
                if cost < best_cost:
                    best_cost = cost
                    best_parent = previous_index
            current_costs.append(best_cost)
            current_parents.append(best_parent)

        costs.append(current_costs)
        parents.append(current_parents)

    final_entry_index = len(matched_entries) - 1
    final_candidate_index = min(
        range(len(costs[final_entry_index])),
        key=lambda candidate_index: costs[final_entry_index][candidate_index],
    )

    assignments: list[tuple[int, dict, str]] = []
    current_candidate_index: int | None = final_candidate_index
    for entry_index in range(final_entry_index, -1, -1):
        if current_candidate_index is None:
            break
        entry = matched_entries[entry_index]
        assignments.append(
            (
                entry["index"],
                entry["candidates"][current_candidate_index],
                entry["matched_by"],
            )
        )
        current_candidate_index = parents[entry_index][current_candidate_index]

    assignments.reverse()
    return assignments

def haversine_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    radius_km = 6_371.0088
    lat1_rad, lon1_rad = math.radians(lat1), math.radians(lon1)
    lat2_rad, lon2_rad = math.radians(lat2), math.radians(lon2)
    delta_lat = lat2_rad - lat1_rad
    delta_lon = lon2_rad - lon1_rad
    a = (
        math.sin(delta_lat / 2) ** 2
        + math.cos(lat1_rad) * math.cos(lat2_rad) * math.sin(delta_lon / 2) ** 2
    )
    return 2 * radius_km * math.asin(math.sqrt(a))

def mean(values: list[float]) -> float:
    return sum(values) / len(values)
