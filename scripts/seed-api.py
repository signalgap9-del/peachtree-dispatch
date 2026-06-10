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
        ("Atlanta", "Savannah", "driver-42"),
        ("Marietta", "Athens", "driver-17"),
        ("Decatur", "Macon", None),
        ("Atlanta", "Augusta", "driver-42"),
        ("Roswell", "Columbus", "driver-17"),
    ]
    for index, (origin, destination, driver_id) in enumerate(samples):
        payload = {
            "origin": {"city": origin, "state": "GA"},
            "destination": {"city": destination, "state": "GA"},
            "promised_at": (
                datetime.now(UTC) + timedelta(hours=6 + index * 2)
            ).isoformat(),
            "driver_id": driver_id,
        }
        result = post(f"{args.api_url.rstrip('/')}/deliveries", payload)
        print(f"created {result['delivery_id']} -> {destination}")


if __name__ == "__main__":
    main()
