from .directions import build_directions, search_places
from .graphql_schema import schema as graphql_schema
from .models import DirectionsRequest, Place, VehicleType
from .risk import location_risk, national_risk
from .road_events import get_road_event_feeds
from .vrp.models import MultiStopRouteRequest, VRPScenario
from .vrp.multi_stop import multi_stop_route_service
from .vrp.optimization_service import vrp_optimization_service
from .weather_snapshot import get_weather_snapshot
import base64

from .weather_raster import get_weather_raster_manifest, get_weather_raster_png


def handler(event: dict, context: object) -> dict:
    method = event.get("method", "GET")
    path = event.get("path", "")
    body = event.get("body") or {}
    query = event.get("query") or {}

    if method == "GET" and path == "/places/search":
        result = search_places(query["q"])
    elif method == "POST" and path == "/directions":
        result = build_directions(DirectionsRequest.model_validate(body))
    elif method == "GET" and path == "/risk/national":
        result = national_risk()
    elif method == "GET" and path == "/risk/weather-snapshot":
        result = get_weather_snapshot()
    elif method == "GET" and path == "/risk/weather-raster":
        result = get_weather_raster_manifest()
    elif method == "GET" and path == "/risk/weather-raster.png":
        return {
            "content_type": "image/png",
            "body_base64": base64.b64encode(get_weather_raster_png()).decode("ascii"),
        }
    elif method == "POST" and path == "/risk/location":
        result = location_risk(Place.model_validate(body))
    elif method == "GET" and path == "/road-events/feeds":
        limit = int(query.get("limit", 30))
        result = get_road_event_feeds(state=query.get("state"), limit=limit)
    elif method == "POST" and path == "/routes/multi-stop":
        result = multi_stop_route_service.plan(MultiStopRouteRequest.model_validate(body))
    elif method == "POST" and path == "/routes/multi-stop/optimize":
        command = MultiStopRouteRequest.model_validate({**body, "mode": "OPTIMIZE_ORDER"})
        result = multi_stop_route_service.plan(command)
    elif method == "POST" and path == "/vrp/solve":
        result = vrp_optimization_service.solve(VRPScenario.model_validate(body))
    elif method == "POST" and path == "/graphql":
        return {"data": _execute_graphql(body)}
    elif method == "GET" and path == "/network":
        # The public weather and routing API must not import the legacy
        # multi-stop optimizer or initialize its persistence layer at startup.
        from .network import build_network
        from .repository_factory import create_repository

        repository = create_repository()
        repository.seed()
        vehicle_type = query.get("vehicle_type")
        result = build_network(
            repository.list(),
            VehicleType(vehicle_type) if vehicle_type else None,
        )
    else:
        raise ValueError(f"Unsupported internal risk-engine operation: {method} {path}")

    if isinstance(result, list):
        return {"data": [item.model_dump(mode="json") for item in result]}
    return {"data": result.model_dump(mode="json")}


def _execute_graphql(body: dict) -> dict:
    query = body.get("query")
    if not isinstance(query, str) or not query.strip():
        return {
            "data": None,
            "errors": [{"message": "GraphQL request body must include a non-empty query string."}],
        }
    variables = body.get("variables")
    if variables is not None and not isinstance(variables, dict):
        return {
            "data": None,
            "errors": [{"message": "GraphQL variables must be an object when provided."}],
        }
    operation_name = body.get("operationName")
    if operation_name is not None and not isinstance(operation_name, str):
        return {
            "data": None,
            "errors": [{"message": "GraphQL operationName must be a string when provided."}],
        }
    result = graphql_schema.execute_sync(
        query,
        variable_values=variables,
        operation_name=operation_name,
    )
    response = {"data": result.data}
    if result.errors:
        response["errors"] = [error.formatted for error in result.errors]
    return response
