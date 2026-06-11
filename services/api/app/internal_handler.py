from .directions import build_directions, search_places
from .models import DirectionsRequest, Place, VehicleType
from .network import build_network
from .repository_factory import create_repository
from .risk import location_risk, national_risk

repository = create_repository()
repository.seed()


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
    elif method == "POST" and path == "/risk/location":
        result = location_risk(Place.model_validate(body))
    elif method == "GET" and path == "/network":
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
