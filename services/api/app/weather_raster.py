import io
import json
import os
from datetime import UTC, datetime, timedelta

import numpy as np
from PIL import Image

from .models import NationalWeatherSnapshot, WeatherRasterManifest
from .weather_snapshot import get_weather_snapshot


BOUNDS = [[-125.0, 24.0], [-66.0, 50.0]]
WIDTH = 960
HEIGHT = 520
_LOCAL_PNG: bytes | None = None
_LOCAL_MANIFEST: WeatherRasterManifest | None = None


def render_weather_raster(snapshot: NationalWeatherSnapshot) -> bytes:
    live = [point for point in snapshot.points if point.data_status == "LIVE"]
    if not live:
        return _transparent_png()
    longitudes = np.linspace(BOUNDS[0][0], BOUNDS[1][0], WIDTH)
    latitudes = np.linspace(BOUNDS[1][1], BOUNDS[0][1], HEIGHT)
    grid_lon, grid_lat = np.meshgrid(longitudes, latitudes)
    weighted = np.zeros((HEIGHT, WIDTH), dtype=np.float32)
    weights = np.zeros((HEIGHT, WIDTH), dtype=np.float32)
    for point in live:
        distance = ((grid_lon - point.longitude) * np.cos(np.radians(grid_lat))) ** 2 + (grid_lat - point.latitude) ** 2
        weight = 1 / np.maximum(distance, 0.18)
        weighted += weight * point.risk_score
        weights += weight
    scores = np.clip(weighted / np.maximum(weights, 0.001), 0, 100)
    rgba = _colorize(scores)
    image = Image.fromarray(rgba, mode="RGBA")
    output = io.BytesIO()
    image.save(output, format="PNG", optimize=True)
    return output.getvalue()


def save_weather_raster(snapshot: NationalWeatherSnapshot, png: bytes) -> WeatherRasterManifest:
    global _LOCAL_MANIFEST, _LOCAL_PNG
    now = datetime.now(UTC)
    bucket = os.getenv("WEATHER_SNAPSHOT_BUCKET")
    if not bucket:
        manifest = WeatherRasterManifest(
            generated_at=now,
            expires_at=now + timedelta(minutes=75),
            layer="composite-driving-risk",
            source="NOAA/NWS interest-grid raster; HRRR/MRMS adapter contract",
            url="http://127.0.0.1:8000/risk/weather-raster.png",
            bounds=BOUNDS,
            point_count=len(snapshot.points),
            coverage=snapshot.coverage,
        )
        _LOCAL_PNG = png
        _LOCAL_MANIFEST = manifest
        return manifest
    import boto3

    client = boto3.client("s3")
    client.put_object(
        Bucket=bucket,
        Key="weather/latest.png",
        Body=png,
        ContentType="image/png",
        CacheControl="max-age=300",
    )
    manifest = WeatherRasterManifest(
        generated_at=now,
        expires_at=now + timedelta(minutes=75),
        layer="composite-driving-risk",
        source="NOAA/NWS interest-grid raster; HRRR/MRMS adapter contract",
        url=client.generate_presigned_url(
            "get_object",
            Params={"Bucket": bucket, "Key": "weather/latest.png"},
            ExpiresIn=3600,
        ),
        bounds=BOUNDS,
        point_count=len(snapshot.points),
        coverage=snapshot.coverage,
    )
    client.put_object(
        Bucket=bucket,
        Key="weather/manifest.json",
        Body=manifest.model_dump_json().encode(),
        ContentType="application/json",
        CacheControl="max-age=300",
    )
    return manifest


def get_weather_raster_manifest() -> WeatherRasterManifest:
    if _LOCAL_MANIFEST:
        return _LOCAL_MANIFEST
    bucket = os.getenv("WEATHER_SNAPSHOT_BUCKET")
    if bucket:
        try:
            import boto3

            client = boto3.client("s3")
            payload = json.loads(client.get_object(Bucket=bucket, Key="weather/manifest.json")["Body"].read())
            payload["url"] = client.generate_presigned_url(
                "get_object",
                Params={"Bucket": bucket, "Key": "weather/latest.png"},
                ExpiresIn=3600,
            )
            return WeatherRasterManifest.model_validate(payload)
        except Exception:
            pass
    snapshot = get_weather_snapshot()
    return save_weather_raster(snapshot, render_weather_raster(snapshot))


def get_weather_raster_png() -> bytes:
    if _LOCAL_PNG:
        return _LOCAL_PNG
    snapshot = get_weather_snapshot()
    png = render_weather_raster(snapshot)
    save_weather_raster(snapshot, png)
    return png


def _colorize(scores: np.ndarray) -> np.ndarray:
    stops = np.array([0, 20, 40, 60, 80, 100], dtype=np.float32)
    colors = np.array(
        [
            [46, 125, 50, 20],
            [102, 187, 106, 55],
            [251, 192, 45, 105],
            [245, 124, 0, 145],
            [211, 47, 47, 175],
            [123, 31, 162, 205],
        ],
        dtype=np.float32,
    )
    channels = [np.interp(scores, stops, colors[:, index]) for index in range(4)]
    return np.stack(channels, axis=-1).astype(np.uint8)


def _transparent_png() -> bytes:
    output = io.BytesIO()
    Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0)).save(output, format="PNG")
    return output.getvalue()
