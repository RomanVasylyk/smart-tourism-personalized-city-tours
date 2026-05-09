from __future__ import annotations

from app.services.feedback_stats import PlannerFeedbackProfile, PlannerFeedbackStats
from app.services.transport_planner import TRANSPORT_MODE_WALK, TravelPlan

BASE_SCORE_MULTIPLIER = 10.0
TRAVEL_MINUTE_PENALTY = 0.35
REPEAT_CATEGORY_PENALTY = 0.8
PREFERRED_POI_BONUS = 32.0

def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(maximum, value))

def rating_signal(value: float | None) -> float:
    if value is None:
        return 0.0
    return clamp((value - 3.0) / 2.0, -1.0, 1.0)

def rate_signal(value: float | None, neutral: float = 0.5) -> float:
    if value is None:
        return 0.0
    return clamp(value - neutral, -1.0, 1.0)

def stats_confidence(stats: PlannerFeedbackStats, full_weight_after: int) -> float:
    if full_weight_after <= 0:
        return 1.0
    return clamp(stats.sample_size / full_weight_after, 0.0, 1.0)

def walking_discomfort_signal(
    feedback_profile: PlannerFeedbackProfile,
    effective_transport_mode: str,
) -> float:
    city_confidence = stats_confidence(feedback_profile.city_stats, 6)
    discomfort = city_confidence * max(0.0, (feedback_profile.city_stats.too_much_walking_rate or 0.0) - 0.35)

    if effective_transport_mode != TRANSPORT_MODE_WALK:
        transport_confidence = stats_confidence(feedback_profile.transport_mode_stats, 4)
        discomfort += transport_confidence * max(
            0.0,
            (feedback_profile.transport_mode_stats.too_much_walking_rate or 0.0) - 0.30,
        )

    return discomfort

def score_candidate(
    poi: dict,
    travel_plan: TravelPlan,
    category_counts: dict[str, int],
    feedback_profile: PlannerFeedbackProfile,
    effective_transport_mode: str,
    preferred_poi_ids: set[int] | None = None,
) -> tuple[float, dict[str, float]]:
    poi_stats = PlannerFeedbackStats.from_row(poi, "poi_feedback_")
    category_stats = PlannerFeedbackStats.from_row(poi, "category_feedback_")
    city_stats = feedback_profile.city_stats
    transport_stats = feedback_profile.transport_mode_stats

    poi_confidence = stats_confidence(poi_stats, 4)
    category_confidence = stats_confidence(category_stats, 10)
    city_confidence = stats_confidence(city_stats, 6)
    transport_confidence = stats_confidence(transport_stats, 4)

    base_component = float(poi["base_score"] or 0.0) * BASE_SCORE_MULTIPLIER
    travel_penalty = travel_plan.duration_minutes * TRAVEL_MINUTE_PENALTY
    repeat_penalty = REPEAT_CATEGORY_PENALTY * category_counts.get(poi["category"], 0)

    poi_bonus = poi_confidence * (
        (rating_signal(poi_stats.average_rating) * 1.8)
        + (rate_signal(poi_stats.interesting_pois_rate) * 1.3)
    )
    category_bonus = category_confidence * (
        (rating_signal(category_stats.average_rating) * 0.9)
        + (rate_signal(category_stats.interesting_pois_rate) * 0.8)
    )
    completion_bonus = (
        (poi_confidence * rate_signal(poi_stats.completion_rate) * 2.0)
        + (category_confidence * rate_signal(category_stats.completion_rate) * 1.0)
        + (city_confidence * rate_signal(city_stats.completion_rate) * 0.7)
    )
    skip_penalty = (
        (poi_confidence * max(0.0, (poi_stats.skip_rate or 0.0) - 0.15) * 4.0)
        + (category_confidence * max(0.0, (category_stats.skip_rate or 0.0) - 0.18) * 2.2)
        + (city_confidence * max(0.0, (city_stats.skip_rate or 0.0) - 0.20) * 1.2)
    )

    walking_penalty = 0.0
    if travel_plan.mode == "walk":
        long_walk_factor = clamp((travel_plan.duration_minutes - 12) / 16, 0.0, 1.5)
        walking_penalty = (
            walking_discomfort_signal(feedback_profile, effective_transport_mode)
            * long_walk_factor
            * travel_plan.duration_minutes
            * 0.55
        )

    transport_bonus = 0.0
    if effective_transport_mode != TRANSPORT_MODE_WALK and travel_plan.mode == "transit":
        transport_bonus = transport_confidence * (
            (rating_signal(transport_stats.average_rating) * 1.2)
            + (rate_signal(transport_stats.convenient_rate) * 1.7)
            + (rate_signal(transport_stats.completion_rate) * 0.7)
            + (rate_signal(transport_stats.interesting_pois_rate) * 0.6)
        )

    preferred_bonus = PREFERRED_POI_BONUS if int(poi["id"]) in (preferred_poi_ids or set()) else 0.0

    final_score = (
        base_component
        + preferred_bonus
        + poi_bonus
        + category_bonus
        + completion_bonus
        + transport_bonus
        - travel_penalty
        - walking_penalty
        - skip_penalty
        - repeat_penalty
    )

    return final_score, {
        "base_score": base_component,
        "preferred_bonus": preferred_bonus,
        "poi_bonus": poi_bonus,
        "category_bonus": category_bonus,
        "completion_bonus": completion_bonus,
        "transport_bonus": transport_bonus,
        "travel_penalty": travel_penalty,
        "walking_penalty": walking_penalty,
        "skip_penalty": skip_penalty,
        "repeat_penalty": repeat_penalty,
        "final_score": final_score,
    }

def rounded_score_breakdown(score_breakdown: dict[str, float]) -> dict[str, float]:
    return {key: round(value, 3) for key, value in score_breakdown.items()}
