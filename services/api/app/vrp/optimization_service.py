from __future__ import annotations

import os

from .cost_model import build_risk_adjusted_matrix
from .edge_risk import EdgeRiskProvider, RuleBasedEdgeRiskProvider
from .matrix import RoutingMatrixProvider, build_default_matrix_provider
from .models import VRPScenario, VRPSolution, scenario_to_nodes
from .solvers.base import VRPSolver
from .solvers.ortools_solver import ORToolsVRPSolver


class VRPOptimizationService:
    def __init__(
        self,
        matrix_provider: RoutingMatrixProvider,
        edge_risk_provider: EdgeRiskProvider,
        solvers: dict[str, VRPSolver],
    ):
        self.matrix_provider = matrix_provider
        self.edge_risk_provider = edge_risk_provider
        self.solvers = solvers

    def solve(self, scenario: VRPScenario) -> VRPSolution:
        nodes = scenario_to_nodes(scenario)
        matrix = self.matrix_provider.build_matrix(nodes)
        adjusted_matrix, edge_costs = build_risk_adjusted_matrix(
            matrix=matrix,
            nodes=nodes,
            config=scenario.cost_model,
            edge_risk_provider=self.edge_risk_provider,
        )
        solver = self.solvers.get(scenario.solver)
        if solver is None:
            raise ValueError(f"Unsupported VRP solver: {scenario.solver}")
        solution = solver.solve(scenario, matrix, adjusted_matrix)
        solution.edge_costs = edge_costs
        solution.source_status.update(
            {
                "routing_matrix": matrix.source_status,
                "edge_risk": "RULE_BASED",
                "ml_cost_model": "SHADOW" if scenario.cost_model.use_ml_shadow_cost else "DISABLED",
            }
        )
        return solution


def build_default_vrp_service() -> VRPOptimizationService:
    return VRPOptimizationService(
        matrix_provider=build_default_matrix_provider(),
        edge_risk_provider=RuleBasedEdgeRiskProvider(),
        solvers={"ortools": ORToolsVRPSolver(time_limit_seconds=int(os.environ.get("VRP_SOLVER_TIME_LIMIT_SECONDS", "5")))},
    )


vrp_optimization_service = build_default_vrp_service()
