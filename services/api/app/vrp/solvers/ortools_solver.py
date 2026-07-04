from __future__ import annotations

import time
from datetime import timedelta

from ..models import RoutingMatrix, VRPScenario, VRPSolution, VRPStop, VRPVehicleRoute


class ORToolsVRPSolver:
    """OR-Tools baseline solver for risk-adjusted CVRP.

    This adapter starts with matrix arc cost, vehicle capacity and optional drop
    penalties. Time windows stay out of this slice so the API can land with a
    stable, tested baseline before adding VRPTW complexity.
    """

    def __init__(self, time_limit_seconds: int = 5):
        self.time_limit_seconds = time_limit_seconds

    def solve(
        self,
        scenario: VRPScenario,
        matrix: RoutingMatrix,
        adjusted_cost_matrix: list[list[int]],
    ) -> VRPSolution:
        from ortools.constraint_solver import pywrapcp, routing_enums_pb2

        started = time.perf_counter()
        node_count = 1 + len(scenario.jobs)
        vehicle_count = len(scenario.vehicles)
        depot_index = 0

        manager = pywrapcp.RoutingIndexManager(node_count, vehicle_count, depot_index)
        routing = pywrapcp.RoutingModel(manager)

        def transit_callback(from_index: int, to_index: int) -> int:
            from_node = manager.IndexToNode(from_index)
            to_node = manager.IndexToNode(to_index)
            return int(adjusted_cost_matrix[from_node][to_node])

        transit_callback_index = routing.RegisterTransitCallback(transit_callback)
        for vehicle_id in range(vehicle_count):
            routing.SetArcCostEvaluatorOfVehicle(transit_callback_index, vehicle_id)

        self._add_capacity_dimension(scenario, routing, manager)
        self._add_drop_penalties(scenario, routing, manager)

        parameters = pywrapcp.DefaultRoutingSearchParameters()
        parameters.first_solution_strategy = routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
        parameters.local_search_metaheuristic = routing_enums_pb2.LocalSearchMetaheuristic.GUIDED_LOCAL_SEARCH
        parameters.time_limit.FromTimedelta(timedelta(seconds=self.time_limit_seconds))

        solution = routing.SolveWithParameters(parameters)
        elapsed_ms = round((time.perf_counter() - started) * 1000)

        if not solution:
            return VRPSolution(
                solver="ortools",
                status="INFEASIBLE",
                objective_value=0,
                solve_time_ms=elapsed_ms,
                routes=[],
                dropped_jobs=[job.job_id for job in scenario.jobs],
                source_status={"solver": "INFEASIBLE"},
            )

        return self._extract_solution(
            scenario=scenario,
            matrix=matrix,
            adjusted_cost_matrix=adjusted_cost_matrix,
            routing=routing,
            manager=manager,
            solution=solution,
            solve_time_ms=elapsed_ms,
        )

    def _add_capacity_dimension(self, scenario: VRPScenario, routing, manager) -> None:
        demands = [0] + [job.demand_units for job in scenario.jobs]
        capacities = [vehicle.capacity_units for vehicle in scenario.vehicles]

        def demand_callback(from_index: int) -> int:
            return demands[manager.IndexToNode(from_index)]

        demand_callback_index = routing.RegisterUnaryTransitCallback(demand_callback)
        routing.AddDimensionWithVehicleCapacity(demand_callback_index, 0, capacities, True, "Capacity")

    def _add_drop_penalties(self, scenario: VRPScenario, routing, manager) -> None:
        for node_index, job in enumerate(scenario.jobs, start=1):
            routing.AddDisjunction([manager.NodeToIndex(node_index)], job.drop_penalty)

    def _extract_solution(
        self,
        scenario: VRPScenario,
        matrix: RoutingMatrix,
        adjusted_cost_matrix: list[list[int]],
        routing,
        manager,
        solution,
        solve_time_ms: int,
    ) -> VRPSolution:
        routes: list[VRPVehicleRoute] = []
        visited_job_ids: set[str] = set()

        for vehicle_id, vehicle in enumerate(scenario.vehicles):
            index = routing.Start(vehicle_id)
            stops: list[VRPStop] = []
            total_duration_seconds = 0.0
            total_distance_meters = 0.0
            risk_adjusted_seconds = 0.0
            sequence = 1

            while not routing.IsEnd(index):
                next_index = solution.Value(routing.NextVar(index))
                from_node = manager.IndexToNode(index)
                to_node = manager.IndexToNode(next_index)

                if to_node != 0 and to_node <= len(scenario.jobs):
                    job = scenario.jobs[to_node - 1]
                    visited_job_ids.add(job.job_id)
                    base_duration = matrix.duration_seconds[from_node][to_node] or 0
                    base_distance = matrix.distance_meters[from_node][to_node] or 0
                    adjusted_cost = adjusted_cost_matrix[from_node][to_node]
                    leg_risk = _risk_from_adjustment(base_duration, adjusted_cost)
                    stops.append(
                        VRPStop(
                            job_id=job.job_id,
                            sequence=sequence,
                            arrival_window_status="UNKNOWN",
                            leg_duration_minutes=round(base_duration / 60, 1),
                            leg_distance_miles=round(base_distance / 1609.344, 1),
                            leg_risk_score=leg_risk,
                            primary_hazard="Weather" if leg_risk >= 45 else None,
                        )
                    )
                    sequence += 1

                total_duration_seconds += matrix.duration_seconds[from_node][to_node] or 0
                total_distance_meters += matrix.distance_meters[from_node][to_node] or 0
                risk_adjusted_seconds += adjusted_cost_matrix[from_node][to_node]
                index = next_index

            if stops:
                exposure = round(sum(stop.leg_risk_score for stop in stops) / len(stops))
                routes.append(
                    VRPVehicleRoute(
                        vehicle_id=vehicle.vehicle_id,
                        vehicle_type=vehicle.vehicle_type,
                        stops=stops,
                        total_duration_minutes=round(total_duration_seconds / 60, 1),
                        total_distance_miles=round(total_distance_meters / 1609.344, 1),
                        risk_adjusted_duration_minutes=round(risk_adjusted_seconds / 60, 1),
                        risk_exposure_score=exposure,
                    )
                )

        dropped = [job.job_id for job in scenario.jobs if job.job_id not in visited_job_ids]
        return VRPSolution(
            solver="ortools",
            status="FEASIBLE",
            objective_value=float(solution.ObjectiveValue()),
            solve_time_ms=solve_time_ms,
            routes=routes,
            dropped_jobs=dropped,
            source_status={"solver": "LIVE"},
        )


def _risk_from_adjustment(base_duration_seconds: float, adjusted_cost_seconds: int) -> int:
    if base_duration_seconds <= 0:
        return 0
    ratio = max(0.0, (adjusted_cost_seconds - base_duration_seconds) / max(base_duration_seconds, 1))
    return min(100, round(ratio * 100))
