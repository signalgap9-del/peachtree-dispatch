from dataclasses import dataclass
from math import hypot

from ortools.constraint_solver import pywrapcp, routing_enums_pb2

from .models import DeliverySummary, WeatherRisk


@dataclass(frozen=True)
class VehicleRoute:
    driver_id: str
    deliveries: list[DeliverySummary]


def solve_routes(
    deliveries: list[DeliverySummary],
    weather_by_city: dict[str, WeatherRisk],
    city_coordinates: dict[str, tuple[float, float]],
) -> list[VehicleRoute]:
    if not deliveries:
        return []
    drivers = sorted({item.driver_id for item in deliveries if item.driver_id})
    if not drivers:
        drivers = ["candidate-driver"]

    nodes = ["Atlanta", *[item.destination.city for item in deliveries]]
    manager = pywrapcp.RoutingIndexManager(len(nodes), len(drivers), 0)
    routing = pywrapcp.RoutingModel(manager)

    def arc_cost(from_index: int, to_index: int) -> int:
        from_city = nodes[manager.IndexToNode(from_index)]
        to_city = nodes[manager.IndexToNode(to_index)]
        from_lon, from_lat = city_coordinates[from_city]
        to_lon, to_lat = city_coordinates[to_city]
        distance = hypot(to_lon - from_lon, to_lat - from_lat) * 1000
        climate_penalty = weather_by_city[to_city].risk_score * 5 if to_city != "Atlanta" else 0
        return round(distance + climate_penalty)

    transit_callback = routing.RegisterTransitCallback(arc_cost)
    routing.SetArcCostEvaluatorOfAllVehicles(transit_callback)
    routing.AddDimension(transit_callback, 0, 100_000, True, "RouteCost")
    cost_dimension = routing.GetDimensionOrDie("RouteCost")
    cost_dimension.SetGlobalSpanCostCoefficient(100)

    for node_index, delivery in enumerate(deliveries, start=1):
        if delivery.driver_id:
            routing.SetAllowedVehiclesForIndex(
                [drivers.index(delivery.driver_id)], manager.NodeToIndex(node_index)
            )

    parameters = pywrapcp.DefaultRoutingSearchParameters()
    parameters.first_solution_strategy = (
        routing_enums_pb2.FirstSolutionStrategy.PATH_CHEAPEST_ARC
    )
    parameters.local_search_metaheuristic = (
        routing_enums_pb2.LocalSearchMetaheuristic.GUIDED_LOCAL_SEARCH
    )
    parameters.time_limit.seconds = 2
    solution = routing.SolveWithParameters(parameters)
    if not solution:
        return _fallback(deliveries, drivers, weather_by_city)

    result: list[VehicleRoute] = []
    for vehicle_id, driver_id in enumerate(drivers):
        index = routing.Start(vehicle_id)
        route_deliveries: list[DeliverySummary] = []
        while not routing.IsEnd(index):
            node_index = manager.IndexToNode(index)
            if node_index:
                route_deliveries.append(deliveries[node_index - 1])
            index = solution.Value(routing.NextVar(index))
        if route_deliveries:
            result.append(VehicleRoute(driver_id=driver_id, deliveries=route_deliveries))
    return result


def _fallback(
    deliveries: list[DeliverySummary],
    drivers: list[str],
    weather_by_city: dict[str, WeatherRisk],
) -> list[VehicleRoute]:
    grouped = {driver: [] for driver in drivers}
    for delivery in deliveries:
        driver = delivery.driver_id or min(grouped, key=lambda item: len(grouped[item]))
        grouped[driver].append(delivery)
    return [
        VehicleRoute(
            driver_id=driver,
            deliveries=sorted(
                jobs,
                key=lambda item: (
                    weather_by_city[item.destination.city].risk_score,
                    item.promised_at,
                ),
            ),
        )
        for driver, jobs in grouped.items()
        if jobs
    ]
