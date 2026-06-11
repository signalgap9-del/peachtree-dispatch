from .weather_snapshot import build_weather_snapshot, save_weather_snapshot
from .weather_raster import render_weather_raster, save_weather_raster


def handler(event: dict, context: object) -> dict:
    snapshot = build_weather_snapshot()
    save_weather_snapshot(snapshot)
    manifest = save_weather_raster(snapshot, render_weather_raster(snapshot))
    return {
        "generated_at": snapshot.generated_at.isoformat(),
        "coverage": snapshot.coverage,
        "points": len(snapshot.points),
        "source_status": snapshot.source_status,
        "raster": manifest.model_dump(mode="json"),
    }
