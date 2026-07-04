from __future__ import annotations

from typing import Protocol

from ..models import RoutingMatrix, VRPScenario, VRPSolution


class VRPSolver(Protocol):
    def solve(
        self,
        scenario: VRPScenario,
        matrix: RoutingMatrix,
        adjusted_cost_matrix: list[list[int]],
    ) -> VRPSolution:
        ...
