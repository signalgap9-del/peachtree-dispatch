# NOAA Raster Worker

This one-shot worker upgrades the low-cost NWS interest-grid raster to an
HRRR-backed national raster without changing the public API or MapLibre layer.

- Uses Herbie's GRIB index filtering to download only HRRR temperature and
  10-meter wind messages from NOAA's AWS Open Data bucket.
- Combines HRRR driving conditions with the latest national MRMS
  precipitation-rate field.
- Produces a transparent nationwide PNG and the shared raster manifest.
- Intended for a scheduled AWS Batch/Fargate Spot task, not an always-on
  service.

Run only after configuring `WEATHER_SNAPSHOT_BUCKET` and AWS credentials:

```bash
docker build -t atmospath-weather-raster services/weather-raster
docker run --rm -e WEATHER_SNAPSHOT_BUCKET=... atmospath-weather-raster
```
