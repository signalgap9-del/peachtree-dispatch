from __future__ import annotations

import itertools
import os

from .cost_model import build_risk_adjusted_matrix, edge_cost_lookup
from .edge_risk import EdgeRiskProvider, build_default_edge_risk_provider
from .geometry import FallbackRouteGeometryProvider, OsrmRouteGeometryProvider, ResilientRouteGeometryProvider, RouteGeometryProvider
from .matrix import RoutingMatrixProvider, build_default_matrix_provider
from .ml.shadow_cost_model import ShadowCostModel, load_shadow_cost_model_from_env
from .models import (
    GeoNode,
    MultiStopMode,
    MultiStopRoutePlan,
    MultiStopRouteRequest,
    RouteLeg,
    RouteStop,
    stop_to_node,
)


class MultiStopRouteService:
    def __init__(
        self,
        matrix_provider: RoutingMatrixProvider,
        edge_risk_provider: EdgeRiskProvider,
        geometry_provider: RouteGeometryProvider,
        shadow_cost_model: ShadowCostModel | None = None,
    ):
        self.matrix_provider = matrix_provider
        self.edge_risk_provider = edge_risk_provider
        self.geometry_provider = geometry_provider
        self.shadow_cost_model = shadow_cost_model

    def plan(self, request: MultiStopRouteRequest) -> MultiStopRoutePlan:
        nodes = [stop_to_node(stop) for stop in request.stops]
        matrix = self.matrix_provider.build_matrix(nodes)
        adjusted_matrix, edge_costs = build_risk_adjusted_matrix(
            matrix=matrix,
            nodes=nodes,
            config=request.risk_model,
            edge_risk_provider=self.edge_risk_provider,
            shadow_cost_model=self.shadow_cost_model,
        )
        if request.mode == MultiStopMode.OPTIMIZE_ORDER:
            ordered_stops, explanation = self._optimized_order(request, nodes, adjusted_matrix)
        else:
            ordered_stops = request.stops
            explanation = ["Submitted stop order was preserved."]

        edge_lookup = edge_cost_lookup(edge_costs)
        legs = self._build_legs(ordered_stops, edge_lookup)
        route_risk = round(sum(leg.risk_score for leg in legs) / len(legs)) if legs else 0
        submitted = [stop.stop_id for stop in request.stops]
        optimized = [stop.stop_id for stop in ordered_stops] if request.mode == MultiStopMode.OPTIMIZE_ORDER else None
        route_geometry_status = "LIVE" if all(leg.geometry.get("sourceStatus") == "LIVE" for leg in legs) else "ESTIMATED"

        return MultiStopRoutePlan(
            mode=request.mode,
            vehicle_type=request.vehicle_type,
            submitted_sequence=submitted,
            optimized_sequence=optimized,
            sequence_changed=bool(optimized and optimized != submitted),
            explanation=explanation,
            total_distance_miles=round(sum(leg.distance_miles for leg in legs), 1),
            total_duration_minutes=round(sum(leg.duration_minutes for leg in legs), 1),
            risk_adjusted_duration_minutes=round(sum(leg.risk_adjusted_duration_minutes for leg in legs), 1),
            route_risk_score=route_risk,
            legs=legs,
            source_status={
                "routing_matrix": matrix.source_status,
                "route_geometry": route_geometry_status,
                "edge_risk": getattr(self.edge_risk_provider, "source_status", "RULE_BASED"),
                "traffic": "UNAVAILABLE",
                "ml_cost_model": self._ml_cost_model_status(request),
            },
        )

    def _ml_cost_model_status(self, request: MultiStopRouteRequest) -> str:
        if not request.risk_model.use_ml_shadow_cost and not request.risk_model.use_ml_served_cost:
            return "DISABLED"
        if self.shadow_cost_model is None or self.shadow_cost_model.model_version == "disabled":
            return "ML_REQUESTED_BUT_DISABLED" if request.risk_model.use_ml_served_cost else "SHADOW_DISABLED"
        mode = "SERVING_REQUESTED" if request.risk_model.use_ml_served_cost else "SHADOW_ARTIFACT"
        return f"{mode}:{self.shadow_cost_model.model_version}"

    def _build_legs(self, ordered_stops: list[RouteStop], edge_lookup) -> list[RouteLeg]:
        legs: list[RouteLeg] = []
        for index, (origin, destination) in enumerate(zip(ordered_stops, ordered_stops[1:]), start=1):
            origin_node = stop_to_node(origin)
            destination_node = stop_to_node(destination)
            geometry = self.geometry_provider.route_leg(origin_node, destination_node)
            edge = edge_lookup[(origin.stop_id, destination.stop_id)]
            risk_score = max(edge.weather_risk_score, edge.traffic_risk_score, edge.flood_risk_score, edge.alert_risk_score)
            legs.append(
                RouteLeg(
                    from_stop_id=origin.stop_id,
                    to_stop_id=destination.stop_id,
                    sequence=index,
                    distance_miles=geometry.distance_miles,
                    duration_minutes=geometry.duration_minutes,
                    risk_adjusted_duration_minutes=round(edge.adjusted_cost_seconds / 60, 1),
                    risk_score=risk_score,
                    primary_hazard=edge.primary_hazard,
                    geometry={
                        "type": "LineString",
                        "coordinates": geometry.coordinates,
                        "sourceStatus": geometry.source_status,
                    },
                    explanation=edge.explanation,
                )
            )
        return legs

    def _optimized_order(
        self,
        request: MultiStopRouteRequest,
        nodes: list[GeoNode],
        adjusted_matrix: list[list[int]],
    ) -> tuple[list[RouteStop], list[str]]:
        by_id = {stop.stop_id: stop for stop in request.stops}
        node_index = {node.node_id: index for index, node in enumerate(nodes)}
        start_id = request.start_stop_id or request.stops[0].stop_id
        end_id = request.end_stop_id or request.stops[-1].stop_id
        middle = [stop.stop_id for stop in request.stops if stop.stop_id not in {start_id, end_id}]

        def sequence_cost(sequence: tuple[str, ...] | list[str]) -> int:
            ids = [start_id, *sequence, end_id]
            return sum(adjusted_matrix[node_index[left]][node_index[right]] for left, right in zip(ids, ids[1:]))

        if len(middle) <= 8:
            best_middle = min(itertools.permutations(middle), key=sequence_cost)
        else:
            best_middle = tuple(self._nearest_neighbor_middle(start_id, end_id, middle, node_index, adjusted_matrix))

        optimized_ids = [start_id, *best_middle, end_id]
        submitted_ids = [stop.stop_id for stop in request.stops]
        baseline_cost = sequence_cost([stop_id for stop_id in submitted_ids if stop_id not in {start_id, end_id}])
        optimized_cost = sequence_cost(best_middle)
        minutes_saved = round((baseline_cost - optimized_cost) / 60, 1)
        explanation = [
            "Optimized stop order minimizes risk-adjusted travel time while keeping start and end fixed.",
            f"Risk-adjusted sequence delta: {minutes_saved:+.1f} minutes versus submitted order.",
        ]
        if optimized_ids == submitted_ids:
            explanation.append("Submitted order was already the best sequence for the current matrix.")
        else:
            explanation.append("Stop order changed because one or more risky or slow legs were avoided.")
        return [by_id[stop_id] for stop_id in optimized_ids], explanation

    def _nearest_neighbor_middle(
        self,
        start_id: str,
        end_id: str,
        middle: list[str],
        node_index: dict[str, int],
        adjusted_matrix: list[list[int]],
    ) -> list[str]:
        remaining = set(middle)
        current = start_id
        sequence: list[str] = []
        while remaining:
            next_stop = min(
                remaining,
                key=lambda candidate: adjusted_matrix[node_index[current]][node_index[candidate]]
                    + adjusted_matrix[node_index[candidate]][node_index[end_id]] * 0.15,
            )
            sequence.append(next_stop)
            remaining.remove(next_stop)
            current = next_stop
        return sequence


def build_default_multi_stop_service() -> MultiStopRouteService:
    osrm_base_url = os.environ.get("OSRM_BASE_URL", "https://router.project-osrm.org")
    timeout_seconds = float(os.environ.get("OSRM_TIMEOUT_SECONDS", "12"))
    return MultiStopRouteService(
        matrix_provider=build_default_matrix_provider(),
        edge_risk_provider=build_default_edge_risk_provider(),
        geometry_provider=ResilientRouteGeometryProvider(
            primary=OsrmRouteGeometryProvider(base_url=osrm_base_url, timeout_seconds=timeout_seconds),
            fallback=FallbackRouteGeometryProvider(),
        ),
        shadow_cost_model=load_shadow_cost_model_from_env(),
    )


multi_stop_route_service = build_default_multi_stop_service()
