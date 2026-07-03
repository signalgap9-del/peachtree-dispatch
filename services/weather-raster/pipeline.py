"""One-shot NOAA raster worker.

The worker follows Herbie's documented HRRR subset workflow and writes the
same S3 contract consumed by the AtmosPath web map:

  weather/latest.png
  weather/manifest.json

It is intentionally a one-shot job, suitable for AWS Batch/Fargate Spot or a
scheduled CI experiment. It must not run as an always-on service.
"""

import io
import json
import os
import gzip
import tempfile
import urllib.request
from datetime import UTC, datetime, timedelta

import boto3
import numpy as np
import xarray as xr
from herbie import HerbieLatest
from PIL import Image
from scipy.interpolate import griddata


BOUNDS = [[-125.0, 24.0], [-66.0, 50.0]]
WIDTH = 1440
HEIGHT = 780


def main() -> dict:
    bucket = os.environ["WEATHER_SNAPSHOT_BUCKET"]
    scores, source = load_hrrr_risk()
    try:
        precipitation, mrms_source = load_mrms_precipitation()
        scores = np.maximum(scores, np.clip(precipitation * 2.5, 0, 100))
        source = f"{source}; {mrms_source}"
    except Exception as error:
        print(json.dumps({"warning": "MRMS precipitation unavailable", "detail": str(error)}))
    png = render(scores)
    client = boto3.client("s3")
    client.put_object(
        Bucket=bucket,
        Key="weather/latest.png",
        Body=png,
        ContentType="image/png",
        CacheControl="max-age=900",
    )
    now = datetime.now(UTC)
    manifest = {
        "generated_at": now.isoformat(),
        "expires_at": (now + timedelta(hours=2)).isoformat(),
        "layer": "hrrr-driving-risk",
        "source": source,
        "url": "",
        "bounds": BOUNDS,
        "point_count": int(scores.size),
        "coverage": round(float(np.isfinite(scores).sum() / scores.size), 3),
        "model_version": "hrrr-raster-v0.1",
    }
    client.put_object(
        Bucket=bucket,
        Key="weather/manifest.json",
        Body=json.dumps(manifest).encode(),
        ContentType="application/json",
        CacheControl="max-age=900",
    )
    summary = {key: manifest[key] for key in ("generated_at", "source", "coverage", "point_count")}
    print(json.dumps(summary))
    return summary


def handler(event, context) -> dict:
    """AWS Lambda entry point for the scheduled HRRR/MRMS raster refresh."""
    return main()


def load_hrrr_risk() -> tuple[np.ndarray, str]:
    """Download only selected HRRR GRIB messages via their byte-range index."""
    hrrr = HerbieLatest(model="hrrr", product="sfc", fxx=1, priority=["aws"])
    temperature = hrrr.xarray(r":TMP:2 m above")
    wind = hrrr.xarray(r":[U|V]GRD:10 m above")
    latitude = np.asarray(temperature.latitude).ravel()
    longitude = np.asarray(temperature.longitude).ravel()
    longitude = np.where(longitude > 180, longitude - 360, longitude)
    fahrenheit = (np.asarray(temperature.t2m).ravel() - 273.15) * 9 / 5 + 32
    speed_mph = np.hypot(np.asarray(wind.u10).ravel(), np.asarray(wind.v10).ravel()) * 2.23694
    risk = np.clip(speed_mph * 1.4 + np.maximum(0, fahrenheit - 95) * 2 + np.maximum(0, 20 - fahrenheit), 0, 100)
    grid_lon, grid_lat = np.meshgrid(
        np.linspace(BOUNDS[0][0], BOUNDS[1][0], WIDTH),
        np.linspace(BOUNDS[1][1], BOUNDS[0][1], HEIGHT),
    )
    raster = griddata(
        np.column_stack([longitude[::8], latitude[::8]]),
        risk[::8],
        (grid_lon, grid_lat),
        method="linear",
        fill_value=0,
    )
    return raster, f"NOAA HRRR via Herbie AWS source: {hrrr.date:%Y-%m-%d %H:%MZ} F01"


def load_mrms_precipitation() -> tuple[np.ndarray, str]:
    """Load the current national MRMS precipitation-rate field."""
    url = os.getenv(
        "MRMS_PRECIP_URL",
        "https://mrms.ncep.noaa.gov/data/2D/PrecipRate/MRMS_PrecipRate_00.00_latest.grib2.gz",
    )
    with urllib.request.urlopen(url, timeout=90) as response:
        compressed = response.read()
    with tempfile.NamedTemporaryFile(suffix=".grib2") as temporary:
        temporary.write(gzip.decompress(compressed))
        temporary.flush()
        dataset = xr.open_dataset(temporary.name, engine="cfgrib")
        variable = next(iter(dataset.data_vars))
        values = np.asarray(dataset[variable]).squeeze()
        latitudes = np.asarray(dataset.latitude)
        longitudes = np.asarray(dataset.longitude)
        if latitudes.ndim == 1 and longitudes.ndim == 1:
            longitudes, latitudes = np.meshgrid(longitudes, latitudes)
        grid_lon, grid_lat = np.meshgrid(
            np.linspace(BOUNDS[0][0], BOUNDS[1][0], WIDTH),
            np.linspace(BOUNDS[1][1], BOUNDS[0][1], HEIGHT),
        )
        raster = griddata(
            np.column_stack([longitudes.ravel()[::8], latitudes.ravel()[::8]]),
            values.ravel()[::8],
            (grid_lon, grid_lat),
            method="linear",
            fill_value=0,
        )
    return np.maximum(raster, 0), "NOAA MRMS current precipitation rate"


def render(scores: np.ndarray) -> bytes:
    stops = np.array([0, 20, 40, 60, 80, 100], dtype=np.float32)
    colors = np.array(
        [[46, 125, 50, 10], [102, 187, 106, 55], [251, 192, 45, 105], [245, 124, 0, 150], [211, 47, 47, 185], [123, 31, 162, 215]],
        dtype=np.float32,
    )
    rgba = np.stack([np.interp(scores, stops, colors[:, index]) for index in range(4)], axis=-1).astype(np.uint8)
    output = io.BytesIO()
    Image.fromarray(rgba, mode="RGBA").save(output, format="PNG", optimize=True)
    return output.getvalue()


if __name__ == "__main__":
    main()
