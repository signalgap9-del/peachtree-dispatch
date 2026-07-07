from __future__ import annotations

import os

from .cost_model import build_risk_adjusted_matrix
from .edge_risk import EdgeRiskProvider, build_default_edge_risk_provider
from .matrix import RoutingMatrixProvider, build_default_matrix_provider
from .ml.shadow_cost_model import ShadowCostModel, load_shadow_cost_model_from_env
from .models import VRPScenario, VRPSolution, scenario_to_nodes
from .solvers.base import VRPSolver
from .solvers.ortools_solver import ORToolsVRPSolver


class VRPOptimizationService:
    def __init__(
        self,
        matrix_provider: RoutingMatrixProvider,
        edge_risk_provider: EdgeRiskProvider,
        solvers: dict[str, VRPSolver],
        shadow_cost_model: ShadowCostModel | None = None,
    ):
        self.matrix_provider = matrix_provider
        self.edge_risk_provider = edge_risk_provider
        self.solvers = solvers
        self.shadow_cost_model = shadow_cost_model

    def solve(self, scenario: VRPScenario) -> VRPSolution:
        nodes = scenario_to_nodes(scenario)
        matrix = self.matrix_provider.build_matrix(nodes)
        adjusted_matrix, edge_costs = build_risk_adjusted_matrix(
            matrix=matrix,
            nodes=nodes,
            config=scenario.cost_model,
            edge_risk_provider=self.edge_risk_provider,
            shadow_cost_model=self.shadow_cost_model,
        )
        solver = self.solvers.get(scenario.solver)
        if solver is None:
            raise ValueError(f"Unsupported VRP solver: {scenario.solver}")
        solution = solver.solve(scenario, matrix, adjusted_matrix)
        solution.edge_costs = edge_costs
        solution.source_status.update(
            {
                "routing_matrix": matrix.source_status,
                "edge_risk": getattr(self.edge_risk_provider, "source_status", "RULE_BASED"),
                "ml_cost_model": self._ml_cost_model_status(scenario),
            }
        )
        return solution

    def _ml_cost_model_status(self, scenario: VRPScenario) -> str:
        if not scenario.cost_model.use_ml_shadow_cost and not scenario.cost_model.use_ml_served_cost:
            return "DISABLED"
        if self.shadow_cost_model is None or self.shadow_cost_model.model_version == "disabled":
            return "ML_REQUESTED_BUT_DISABLED" if scenario.cost_model.use_ml_served_cost else "SHADOW_DISABLED"
        mode = "SERVING_REQUESTED" if scenario.cost_model.use_ml_served_cost else "SHADOW_ARTIFACT"
        return f"{mode}:{self.shadow_cost_model.model_version}"


def build_default_vrp_service() -> VRPOptimizationService:
    return VRPOptimizationService(
        matrix_provider=build_default_matrix_provider(),
        edge_risk_provider=build_default_edge_risk_provider(),
        solvers={"ortools": ORToolsVRPSolver(time_limit_seconds=int(os.environ.get("VRP_SOLVER_TIME_LIMIT_SECONDS", "5")))},
        shadow_cost_model=load_shadow_cost_model_from_env(),
    )


vrp_optimization_service = build_default_vrp_service()
