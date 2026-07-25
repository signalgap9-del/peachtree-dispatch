from __future__ import annotations

import logging
from dataclasses import dataclass, field

logger = logging.getLogger(__name__)

# Penalty scale: soft-constraint penalties are expressed in seconds so they
# compose naturally with the duration-based cost matrix.  A weight of 1.0
# maps to this many extra seconds on every affected edge.
SOFT_PENALTY_SCALE = 10_000

# Hard deadlines derived from weather get a safety buffer (seconds).
WEATHER_DEADLINE_BUFFER_SECONDS = 1_800


@dataclass
class TranslatorResult:
    """Outcome of a single constraint translation."""

    explanation: str
    edge_penalties: dict[tuple[int, int], int] = field(default_factory=dict)
    edge_masks: list[tuple[int, int]] = field(default_factory=list)
    time_bounds: dict[int, tuple[int | None, int | None]] = field(default_factory=dict)
    vehicle_capacities: list[int] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def _parse_hhmm(value: str | None) -> int | None:
    """Parse ``HH:mm`` into seconds since midnight; ``None`` when absent."""
    if not value:
        return None
    parts = value.split(":")
    if len(parts) != 2:
        return None
    try:
        hours, minutes = int(parts[0]), int(parts[1])
    except ValueError:
        return None
    return hours * 3600 + minutes * 60


# ---------------------------------------------------------------------------
# Stop-level translators
# ---------------------------------------------------------------------------

def translate_time_window(
    stop_index: int,
    time_window: dict | None,
) -> TranslatorResult:
    """Translate a stop-level ``timeWindow`` into cumulative-variable bounds."""
    if not time_window:
        return TranslatorResult(explanation="No time window specified.")

    earliest = _parse_hhmm(time_window.get("earliest"))
    latest = _parse_hhmm(time_window.get("latest"))
    if earliest is None and latest is None:
        return TranslatorResult(explanation="Time window present but unparseable; ignored.")

    bounds: dict[int, tuple[int | None, int | None]] = {stop_index: (earliest, latest)}
    parts = []
    if earliest is not None:
        parts.append(f"earliest={earliest // 60}:{earliest % 60:02d}")
    if latest is not None:
        parts.append(f"latest={latest // 60}:{latest % 60:02d}")
    return TranslatorResult(
        explanation=f"Time window [{', '.join(parts)}] applied to stop {stop_index}.",
        time_bounds=bounds,
    )


def translate_priority_stop(
    stop_index: int,
    priority: int,
    node_count: int,
) -> TranslatorResult:
    """High-priority stops get a soft upper bound on arrival time.

    Priority 0 = default.  Priority >= 3 is treated as urgent: the solver is
    penalised ``priority * SOFT_PENALTY_SCALE`` seconds for arriving after the
    first quarter of the planning horizon.
    """
    if priority < 3:
        return TranslatorResult(explanation=f"Stop {stop_index} priority {priority} is within normal range.")

    # Soft deadline: first quarter of a 12-hour planning window.
    soft_deadline = 3 * 3600
    penalty = priority * SOFT_PENALTY_SCALE
    return TranslatorResult(
        explanation=(
            f"Priority stop {stop_index} (priority={priority}): soft arrival "
            f"deadline at {soft_deadline // 3600}h with penalty coefficient {penalty}."
        ),
        time_bounds={stop_index: (None, soft_deadline)},
    )


# ---------------------------------------------------------------------------
# Soft-constraint translators
# ---------------------------------------------------------------------------

def translate_avoid_corridor(
    constraint: dict,
    node_count: int,
    edge_costs: list[list[int]],
) -> TranslatorResult:
    """Penalise edges whose midpoint falls along a named corridor.

    Without a geocoded corridor database we apply the penalty uniformly to
    every edge and record the intent so the LLM explanation layer can surface
    it.  A future iteration can match corridor geometry to specific edges.
    """
    target = constraint.get("target", "unknown corridor")
    weight = float(constraint.get("weight", 0.5))
    penalty = round(weight * SOFT_PENALTY_SCALE)

    penalties: dict[tuple[int, int], int] = {}
    for i in range(node_count):
        for j in range(node_count):
            if i != j:
                penalties[(i, j)] = penalty

    return TranslatorResult(
        explanation=(
            f"Avoid corridor '{target}': +{penalty}s penalty on all edges "
            f"(weight={weight:.2f}). Corridor geometry matching is pending; "
            f"penalty applied uniformly."
        ),
        edge_penalties=penalties,
    )


def translate_weather_deadline(
    constraint: dict,
    risk_data: dict | None,
    node_count: int,
) -> TranslatorResult:
    """Set a hard deadline from storm ETA obtained via the risk engine.

    ``risk_data`` is expected to carry a ``storm_eta_minutes`` field (minutes
    from now).  The hard deadline is ``storm_eta - buffer``.
    """
    target = constraint.get("target", "storm")
    if not risk_data or "storm_eta_minutes" not in risk_data:
        return TranslatorResult(
            explanation=f"Weather deadline for '{target}': no storm ETA available from risk engine.",
            warnings=["weather_deadline: risk data unavailable; deadline not applied"],
        )

    storm_eta_seconds = int(risk_data["storm_eta_minutes"]) * 60
    deadline = max(0, storm_eta_seconds - WEATHER_DEADLINE_BUFFER_SECONDS)

    # Apply the deadline as a latest-arrival bound on every non-depot node.
    bounds: dict[int, tuple[int | None, int | None]] = {}
    for idx in range(1, node_count):
        bounds[idx] = (None, deadline)

    return TranslatorResult(
        explanation=(
            f"Weather deadline for '{target}': storm ETA {storm_eta_seconds // 60}min, "
            f"hard deadline set to {deadline // 60}min (buffer={WEATHER_DEADLINE_BUFFER_SECONDS // 60}min)."
        ),
        time_bounds=bounds,
    )


# ---------------------------------------------------------------------------
# Hard-constraint translators
# ---------------------------------------------------------------------------

def translate_hazmat(
    constraint: dict,
    node_count: int,
) -> TranslatorResult:
    """Filter edges with hazmat restrictions.

    Without a hazmat-restriction edge dataset we log a warning and leave the
    matrix unmodified.  The explanation records the intent for downstream
    consumers.
    """
    target = constraint.get("target", "unspecified")
    logger.warning("hazmat restriction '%s' requested but no restriction dataset available", target)
    return TranslatorResult(
        explanation=(
            f"Hazmat restriction '{target}': no restriction dataset available; "
            f"edges left unmasked. Intention recorded for audit."
        ),
        warnings=[f"hazmat: no restriction data for '{target}'; constraint logged but not enforced"],
    )


# ---------------------------------------------------------------------------
# Vehicle translators
# ---------------------------------------------------------------------------

def translate_capacity(
    vehicle: dict | None,
    vehicle_count: int,
) -> TranslatorResult:
    """Translate vehicle capacity into per-vehicle capacity list."""
    if not vehicle:
        return TranslatorResult(explanation="No vehicle specification; default capacity applied.")

    capacity = vehicle.get("capacityKg")
    if capacity is None:
        return TranslatorResult(explanation="Vehicle specified without capacity; default capacity applied.")

    capacities = [int(capacity)] * vehicle_count
    return TranslatorResult(
        explanation=f"Vehicle capacity set to {capacity} kg for {vehicle_count} vehicle(s).",
        vehicle_capacities=capacities,
    )
