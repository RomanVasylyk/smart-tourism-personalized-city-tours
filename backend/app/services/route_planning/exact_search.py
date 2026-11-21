from __future__ import annotations

import time
from dataclasses import dataclass
from datetime import datetime, timedelta

from app.services.route_planning.models import CandidatePoi
from app.services.route_planning.opening_hours import resolve_visit_wait
from app.services.route_planning.scoring import REPEAT_CATEGORY_PENALTY

DEFAULT_VISIT_MINUTES = 20
TIME_CHECK_INTERVAL = 1024
EPSILON = 1e-9


def leg_minutes(seconds: float) -> int:
    if seconds <= 0:
        return 0
    return max(1, round(seconds / 60))


def visit_minutes(poi: CandidatePoi) -> int:
    return poi.visit_duration_min or DEFAULT_VISIT_MINUTES


def route_value(pois: list[CandidatePoi], values_by_id: dict[int, float]) -> float:
    category_counts: dict[str, int] = {}
    total = 0.0
    for poi in pois:
        count = category_counts.get(poi.category, 0)
        total += values_by_id[poi.id] - REPEAT_CATEGORY_PENALTY * count
        category_counts[poi.category] = count + 1
    return total


@dataclass(frozen=True)
class ExactProblem:
    candidates: list[CandidatePoi]
    values: list[float]
    matrix_seconds: list[list[float | None]]
    start_dt: datetime
    available_minutes: int
    return_to_start: bool
    respect_opening_hours: bool
    max_wait_minutes: int
    preferred_ids: list[int]


@dataclass(frozen=True)
class ExactSearchResult:
    order: list[CandidatePoi] | None
    value: float
    used_minutes: int
    optimal: bool
    nodes: int


class _Timeout(Exception):
    pass


class _ExactSearch:
    def __init__(
        self,
        problem: ExactProblem,
        *,
        use_bound: bool,
        use_dominance: bool,
        time_limit_seconds: float | None,
        incumbent_value: float | None,
    ):
        self.problem = problem
        self.use_bound = use_bound
        self.use_dominance = use_dominance
        self.deadline = None if time_limit_seconds is None else time.perf_counter() + time_limit_seconds
        self.n = len(problem.candidates)
        self.budget = problem.available_minutes

        size = self.n + 1
        self.minutes: list[list[int | None]] = [
            [
                None if problem.matrix_seconds[i][j] is None else leg_minutes(problem.matrix_seconds[i][j])
                for j in range(size)
            ]
            for i in range(size)
        ]
        self.visit = [0] + [visit_minutes(poi) for poi in problem.candidates]
        self.value = [0.0] + list(problem.values)
        categories = sorted({poi.category for poi in problem.candidates})
        category_index = {category: index for index, category in enumerate(categories)}
        self.category = [-1] + [category_index[poi.category] for poi in problem.candidates]
        self.category_counts = [0] * len(categories)
        self.category_masks = [0] * len(categories)
        for index in range(1, size):
            self.category_masks[self.category[index]] |= 1 << (index - 1)

        preferred_positions = {poi_id: position for position, poi_id in enumerate(problem.preferred_ids)}
        self.preferred_rank = [-1] + [preferred_positions.get(poi.id, -1) for poi in problem.candidates]
        self.forced = [False] + [rank >= 0 for rank in self.preferred_rank[1:]]
        self.preferred_sequence = sorted(
            (index for index in range(1, size) if self.forced[index]),
            key=lambda index: self.preferred_rank[index],
        )
        self.preferred_mask = 0
        for index in self.preferred_sequence:
            self.preferred_mask |= 1 << (index - 1)

        self.return_minutes = [0] * size
        if problem.return_to_start:
            self.return_minutes = [0] + [self.minutes[index][0] for index in range(1, size)]

        self.wait_cache: list[dict[int, int | None]] = [{} for _ in range(size)]
        self.always_open = [True] + [
            not problem.respect_opening_hours or poi.planning_opening_hours is None for poi in problem.candidates
        ]
        self.min_incoming = [0] * size
        for target in range(1, size):
            incoming = [self.minutes[source][target] for source in range(size) if source != target]
            reachable = [value for value in incoming if value is not None]
            self.min_incoming[target] = min(reachable) if reachable else 10**9

        self.unsafe_until = [-1] * size
        self.sensitive: list[int] = []
        self.sensitive_mask = 0
        for index in range(1, size):
            self.unsafe_until[index] = self._last_harmful_arrival(index)
            if self.unsafe_until[index] >= 0:
                self.sensitive.append(index)
                self.sensitive_mask |= 1 << (index - 1)

        self.weight = [0] + [self.visit[index] + self.min_incoming[index] for index in range(1, size)]
        self.bound_candidates = [index for index in range(1, size) if not self.forced[index] and self.value[index] > 0]
        returns = [value for value in self.return_minutes[1:] if value is not None]
        self.min_return = min(returns) if problem.return_to_start and returns else 0
        self.branch_order = sorted(
            range(1, size),
            key=lambda index: self.value[index] / max(1, self.weight[index]),
            reverse=True,
        )

        self.best_value = -float("inf") if incumbent_value is None else incumbent_value
        self.best_used = 10**9
        self.best_path: list[int] | None = None
        self.path: list[int] = []
        self.labels: dict[tuple[int, int], int] = {}
        self.nodes = 0
        self.timed_out = False

    def wait_at(self, index: int, arrival_minute: int) -> int | None:
        if self.always_open[index]:
            return 0
        cache = self.wait_cache[index]
        if arrival_minute in cache:
            return cache[arrival_minute]
        poi = self.problem.candidates[index - 1]
        wait = resolve_visit_wait(
            poi.planning_opening_hours,
            self.problem.start_dt + timedelta(minutes=arrival_minute),
            self.visit[index],
            respect_opening_hours=self.problem.respect_opening_hours,
            is_forced=self.forced[index],
            max_wait_minutes=self.problem.max_wait_minutes,
        )
        cache[arrival_minute] = wait
        return wait

    def _last_harmful_arrival(self, index: int) -> int:
        poi = self.problem.candidates[index - 1]
        if self.forced[index] or not self.problem.respect_opening_hours or poi.planning_opening_hours is None:
            return -1
        last = -1
        previous_feasible = self.wait_at(index, 0) is not None
        for arrival in range(1, self.budget + 1):
            feasible = self.wait_at(index, arrival) is not None
            if feasible and not previous_feasible:
                last = arrival - 1
            previous_feasible = feasible
        return last

    def _dominance_is_safe(self, elapsed: int, mask: int) -> bool:
        for index in self.sensitive:
            if mask & (1 << (index - 1)):
                continue
            if elapsed + self.min_incoming[index] <= self.unsafe_until[index]:
                return False
        return True

    def _upper_bound(self, mask: int, elapsed: int, score: float, counts: list[int]) -> float:
        total = score
        for index in self.preferred_sequence:
            if not mask & (1 << (index - 1)):
                total += self.value[index]
        capacity = self.budget - elapsed - self.min_return
        if capacity <= 0:
            return total
        items = []
        for index in self.bound_candidates:
            if mask & (1 << (index - 1)):
                continue
            gain = self.value[index] - REPEAT_CATEGORY_PENALTY * counts[self.category[index]]
            if gain > 0:
                items.append((gain / self.weight[index], gain, self.weight[index]))
        items.sort(reverse=True)
        for _, gain, weight in items:
            if weight <= capacity:
                total += gain
                capacity -= weight
            else:
                total += gain * capacity / weight
                break
        return total

    def _next_preferred(self, mask: int) -> int | None:
        for index in self.preferred_sequence:
            if not mask & (1 << (index - 1)):
                return index
        return None

    def _record(self, last: int, mask: int, elapsed: int, score: float) -> None:
        if mask & self.preferred_mask != self.preferred_mask:
            return
        used = elapsed + ((self.return_minutes[last] or 0) if last else 0)
        if score > self.best_value + EPSILON or (
            abs(score - self.best_value) <= EPSILON and self.best_path is not None and used < self.best_used
        ):
            self.best_value = score
            self.best_used = used
            self.best_path = list(self.path)

    def _search(self, last: int, mask: int, elapsed: int, score: float) -> None:
        self.nodes += 1
        if self.deadline is not None and self.nodes % TIME_CHECK_INTERVAL == 0 and time.perf_counter() > self.deadline:
            raise _Timeout()

        self._record(last, mask, elapsed, score)
        if (
            self.use_bound
            and self._upper_bound(mask, elapsed, score, self.category_counts) <= self.best_value + EPSILON
        ):
            return

        next_preferred = self._next_preferred(mask)
        for index in self.branch_order:
            bit = 1 << (index - 1)
            if mask & bit:
                continue
            if self.forced[index] and index != next_preferred:
                continue
            travel = self.minutes[last][index]
            if travel is None:
                continue
            arrival = elapsed + travel
            wait = self.wait_at(index, arrival)
            if wait is None:
                continue
            new_elapsed = arrival + wait + self.visit[index]
            if not self.forced[index]:
                back = self.return_minutes[index]
                if back is None or new_elapsed + back > self.budget:
                    continue
            category = self.category[index]
            gain = self.value[index] - REPEAT_CATEGORY_PENALTY * self.category_counts[category]
            if not self.forced[index] and gain <= 0 and not (self.sensitive_mask & ~mask):
                continue
            new_mask = mask | bit
            if self.use_dominance:
                key = (new_mask, index)
                stored = self.labels.get(key)
                if stored is not None and stored <= new_elapsed and self._dominance_is_safe(stored, new_mask):
                    continue
                if stored is None or new_elapsed < stored:
                    self.labels[key] = new_elapsed
            self.category_counts[category] += 1
            self.path.append(index)
            try:
                self._search(index, new_mask, new_elapsed, score + gain)
            finally:
                self.path.pop()
                self.category_counts[category] -= 1

    def _counts(self, mask: int) -> list[int]:
        return [(mask & category_mask).bit_count() for category_mask in self.category_masks]

    def _insert_label(self, level: dict, key: tuple[int, int], label: tuple) -> None:
        labels = level.get(key)
        if labels is None:
            level[key] = [label]
            return
        mask = key[0]
        elapsed = label[0]
        for existing in labels:
            if existing[0] <= elapsed and self._dominance_is_safe(existing[0], mask):
                return
        if self._dominance_is_safe(elapsed, mask):
            labels[:] = [existing for existing in labels if existing[0] < elapsed]
        labels.append(label)

    def _search_levels(self) -> None:
        level: dict[tuple[int, int], list[tuple]] = {(0, 0): [(0, 0.0, None)]}
        while level:
            next_level: dict[tuple[int, int], list[tuple]] = {}
            for (mask, last), labels in level.items():
                counts = self._counts(mask)
                next_preferred = self._next_preferred(mask)
                for label in labels:
                    elapsed, score, _ = label
                    self.nodes += 1
                    if (
                        self.deadline is not None
                        and self.nodes % TIME_CHECK_INTERVAL == 0
                        and time.perf_counter() > self.deadline
                    ):
                        raise _Timeout()
                    self._record_label(last, mask, label)
                    if self.use_bound and self._upper_bound(mask, elapsed, score, counts) <= self.best_value + EPSILON:
                        continue
                    for index in self.branch_order:
                        bit = 1 << (index - 1)
                        if mask & bit:
                            continue
                        if self.forced[index] and index != next_preferred:
                            continue
                        travel = self.minutes[last][index]
                        if travel is None:
                            continue
                        arrival = elapsed + travel
                        wait = self.wait_at(index, arrival)
                        if wait is None:
                            continue
                        new_elapsed = arrival + wait + self.visit[index]
                        if not self.forced[index]:
                            back = self.return_minutes[index]
                            if back is None or new_elapsed + back > self.budget:
                                continue
                        gain = self.value[index] - REPEAT_CATEGORY_PENALTY * counts[self.category[index]]
                        if not self.forced[index] and gain <= 0 and not (self.sensitive_mask & ~mask):
                            continue
                        self._insert_label(next_level, (mask | bit, index), (new_elapsed, score + gain, (label, index)))
            level = next_level

    def _record_label(self, last: int, mask: int, label: tuple) -> None:
        if mask & self.preferred_mask != self.preferred_mask:
            return
        elapsed, score, _ = label
        used = elapsed + ((self.return_minutes[last] or 0) if last else 0)
        if score > self.best_value + EPSILON or (
            abs(score - self.best_value) <= EPSILON and self.best_path is not None and used < self.best_used
        ):
            self.best_value = score
            self.best_used = used
            path = []
            current = label
            while current[2] is not None:
                parent, index = current[2]
                path.append(index)
                current = parent
            self.best_path = path[::-1]

    def run(self) -> ExactSearchResult:
        try:
            if self.use_dominance:
                self._search_levels()
            else:
                self._search(0, 0, 0, 0.0)
        except _Timeout:
            self.timed_out = True
        return ExactSearchResult(
            order=None if self.best_path is None else [self.problem.candidates[index - 1] for index in self.best_path],
            value=self.best_value,
            used_minutes=self.best_used,
            optimal=not self.timed_out,
            nodes=self.nodes,
        )


def solve_exact(
    problem: ExactProblem,
    *,
    use_bound: bool = True,
    use_dominance: bool = True,
    time_limit_seconds: float | None = None,
    incumbent_value: float | None = None,
) -> ExactSearchResult:
    return _ExactSearch(
        problem,
        use_bound=use_bound,
        use_dominance=use_dominance,
        time_limit_seconds=time_limit_seconds,
        incumbent_value=incumbent_value,
    ).run()
