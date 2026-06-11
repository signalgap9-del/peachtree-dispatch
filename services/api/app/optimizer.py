from dataclasses import dataclass
from math import hypot

from ortools.constraint_solver import pywrapcp, routing_enums_pb2

from .models import DeliverySummary, VehicleType, WeatherRisk


VEHICLE_PROFILES = {
    VehicleType.CAR: {"distance": 1.0, "climate": 1.0, "duration": 1.0},
    VehicleType.VAN: {"distance": 1.04, "climate": 1.12, "duration": 1.08},
    VehicleType.TRUCK: {"distance": 1.1, "climate": 1.3, "duration": 1.18},
}
DEFAULT_VEHICLE_TYPES = (VehicleType.CAR, VehicleType.VAN, VehicleType.TRUCK)


@dataclass(frozen=True)
class VehicleRoute:
    driver_id: str
    vehicle_type: VehicleType
    deliveries: list[DeliverySummary]


def solve_routes(
    deliveries: list[DeliverySummary],
    weather_by_city: dict[str, WeatherRisk],
    city_coordinates: dict[str, tuple[float, float]],
    preferred_vehicle_type: VehicleType | None = None,
    risk_adjusted_matrix: list[list[int]] | None = None,
) -> list[VehicleRoute]:
    if not deliveries:
        return []
    drivers = sorted({item.driver_id for item in deliveries if item.driver_id})
    if not drivers:
        drivers = ["candidate-driver"]
    vehicle_types = [
        preferred_vehicle_type or DEFAULT_VEHICLE_TYPES[index % len(DEFAULT_VEHICLE_TYPES)]
        for index in range(len(drivers))
    ]

    node_coordinates = [city_coordinates["Atlanta"]]
    node_coordinates.extend(
        (
            item.destination.longitude,
            item.destination.latitude,
        )
        if item.destination.longitude is not None and item.destination.latitude is not None
        else city_coordinates[item.destination.city]
        for item in deliveries
    )
    manager = pywrapcp.RoutingIndexManager(len(node_coordinates), len(drivers), 0)
    routing = pywrapcp.RoutingModel(manager)

    callbacks: list[int] = []
    for vehicle_id, vehicle_type in enumerate(vehicle_types):
        profile = VEHICLE_PROFILES[vehicle_type]

        def arc_cost(from_index: int, to_index: int, profile=profile) -> int:
            from_node = manager.IndexToNode(from_index)
            to_node = manager.IndexToNode(to_index)
            if risk_adjusted_matrix is not None:
                return round(risk_adjusted_matrix[from_node][to_node] * profile["climate"])
            from_lon, from_lat = node_coordinates[from_node]
            to_lon, to_lat = node_coordinates[to_node]
            distance = hypot(to_lon - from_lon, to_lat - from_lat) * 1000
            climate_penalty = (
                weather_by_city[deliveries[to_node - 1].destination.city].risk_score * 5
                if to_node
                else 0
            )
            return round(
                distance * profile["distance"] + climate_penalty * profile["climate"]
            )

        callback = routing.RegisterTransitCallback(arc_cost)
        callbacks.append(callback)
        routing.SetArcCostEvaluatorOfVehicle(callback, vehicle_id)

    for node_index, delivery in enumerate(deliveries, start=1):
        if delivery.driver_id:
            routing.VehicleVar(manager.NodeToIndex(node_index)).SetValue(
                drivers.index(delivery.driver_id)
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
        return _fallback(deliveries, drivers, vehicle_types, weather_by_city)

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
            result.append(
                VehicleRoute(
                    driver_id=driver_id,
                    vehicle_type=vehicle_types[vehicle_id],
                    deliveries=route_deliveries,
                )
            )
    return result


def _fallback(
    deliveries: list[DeliverySummary],
    drivers: list[str],
    vehicle_types: list[VehicleType],
    weather_by_city: dict[str, WeatherRisk],
) -> list[VehicleRoute]:
    grouped = {driver: [] for driver in drivers}
    for delivery in deliveries:
        driver = delivery.driver_id or min(grouped, key=lambda item: len(grouped[item]))
        grouped[driver].append(delivery)
    return [
        VehicleRoute(
            driver_id=driver,
            vehicle_type=vehicle_types[drivers.index(driver)],
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
