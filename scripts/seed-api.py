import argparse
import json
from datetime import UTC, datetime, timedelta
from urllib.request import Request, urlopen


def post(url: str, payload: dict) -> dict:
    request = Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=30) as response:
        return json.load(response)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("api_url")
    args = parser.parse_args()
    samples = [
        ("Atlanta", "Savannah", "2 W Bay St, Savannah, GA 31401", 32.0809, -81.0918, "driver-42"),
        ("Marietta", "Athens", "100 N Jackson St, Athens, GA 30601", 33.9584, -83.3738, "driver-17"),
        ("Decatur", "Macon", "700 Poplar St, Macon, GA 31201", 32.8368, -83.6294, None),
        ("Atlanta", "Augusta", "601 Greene St, Augusta, GA 30901", 33.4707, -81.9637, "driver-42"),
        ("Roswell", "Columbus", "100 10th St, Columbus, GA 31901", 32.4657, -84.9890, "driver-17"),
    ]
    for index, (origin, destination, address, latitude, longitude, driver_id) in enumerate(samples):
        payload = {
            "origin": {"city": origin, "state": "GA"},
            "destination": {
                "city": destination,
                "state": "GA",
                "address": address,
                "latitude": latitude,
                "longitude": longitude,
            },
            "promised_at": (
                datetime.now(UTC) + timedelta(hours=6 + index * 2)
            ).isoformat(),
            "driver_id": driver_id,
        }
        result = post(f"{args.api_url.rstrip('/')}/deliveries", payload)
        print(f"created {result['delivery_id']} -> {destination}")


if __name__ == "__main__":
    main()
