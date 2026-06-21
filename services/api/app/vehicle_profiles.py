from .models import VehicleType


VEHICLE_PROFILES = {
    VehicleType.CAR: {"distance": 1.0, "climate": 1.0, "duration": 1.0},
    VehicleType.VAN: {"distance": 1.04, "climate": 1.12, "duration": 1.08},
    VehicleType.TRUCK: {"distance": 1.1, "climate": 1.3, "duration": 1.18},
}
