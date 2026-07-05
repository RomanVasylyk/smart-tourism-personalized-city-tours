from __future__ import annotations

from typing import Callable, Iterator, Optional, TypeVar

T = TypeVar("T")

OR_OPT_MAX_SEGMENT = 3


def improve_order(
    order: list[T],
    *,
    cost: Callable[[list[T]], Optional[float]],
    max_passes: int = 6,
    max_evaluations: int = 600,
) -> list[T]:
    """Improve a visiting order with 2-opt and or-opt local search.

    ``cost`` returns the value to minimise (e.g. total travel minutes) for a
    candidate order, or ``None`` when that order is infeasible (it violates a
    time window or the time budget). Only feasible, strictly cheaper neighbours
    are accepted, so the returned order is never worse than the input one.

    ``max_passes``/``max_evaluations`` bound the work so route generation stays
    responsive; the search stops early once no improving neighbour is found.
    """
    if len(order) < 3:
        return list(order)

    best_order = list(order)
    best_cost = cost(best_order)
    if best_cost is None:
        return list(order)

    evaluations = 1
    for _ in range(max(0, max_passes)):
        if evaluations >= max_evaluations:
            break

        improved_order: Optional[list[T]] = None
        improved_cost = best_cost
        for neighbor in _neighbors(best_order):
            if evaluations >= max_evaluations:
                break
            evaluations += 1
            neighbor_cost = cost(neighbor)
            if neighbor_cost is not None and neighbor_cost + 1e-9 < improved_cost:
                improved_order = neighbor
                improved_cost = neighbor_cost

        if improved_order is None:
            break
        best_order = improved_order
        best_cost = improved_cost

    return best_order


def _neighbors(order: list[T]) -> Iterator[list[T]]:
    n = len(order)

    # 2-opt: reverse the segment between positions i and j (removes crossings).
    for i in range(n - 1):
        for j in range(i + 1, n):
            yield order[:i] + order[i : j + 1][::-1] + order[j + 1 :]

    # or-opt: relocate a run of 1..3 consecutive stops to another position.
    for segment_length in range(1, min(OR_OPT_MAX_SEGMENT, n - 1) + 1):
        for i in range(n - segment_length + 1):
            segment = order[i : i + segment_length]
            rest = order[:i] + order[i + segment_length :]
            for k in range(len(rest) + 1):
                neighbor = rest[:k] + segment + rest[k:]
                if neighbor != order:
                    yield neighbor
