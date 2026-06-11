# Weather Risk Architecture

## Product Model

AtmosPath separates weather risk into three horizons:

1. **Active hazards:** current NWS watches, warnings, and advisories.
2. **Near-term driving conditions:** hourly NOAA/NWS forecasts for a curated
   interest grid plus route-specific weather samples.
3. **Long-term location risk:** FEMA National Risk Index and flood-hazard data,
   planned as a separate cached layer.

The UI must not claim that a flood, hurricane, or other hazard is occurring
unless an authoritative source currently reports it.

## Current Score

The selected-location score is the maximum of:

- the most severe active NWS alert score; and
- a weighted near-term weather score using precipitation, wind, heat, and flood
  alert factors.

Levels:

| Score | Level |
| --- | --- |
| 0-29 | Low |
| 30-54 | Moderate |
| 55-79 | High |
| 80-100 | Severe |

Scores are decision-support summaries, not emergency guidance. Alert detail and
official instructions remain visible when available.

## Data And Cost

- NWS alerts: free official API. Nationwide responses are cached for 60 seconds,
  exceeding the provider's recommendation to request no more often than every
  30 seconds.
- NOAA/NWS interest grid: roughly 200 major-city and Interstate-corridor points
  refreshed hourly by EventBridge and a 512 MB Lambda. The latest JSON snapshot
  is stored privately in S3 and retained for three days.
- Open-Meteo: optional route-specific provider. When it is unavailable, route
  samples can use a nearby live NOAA/NWS interest-grid point instead of fake
  weather.
- FEMA NRI/NFHL: planned static ingestion into low-cost object storage or
  pre-generated map tiles.
- No always-on paid weather service is required for the portfolio deployment.

## Low-Cost Data Flow

```text
EventBridge rate(1 hour)
  -> weather-collector Lambda
  -> NWS points + hourly forecast APIs
  -> S3 weather/latest.json
  -> private risk-engine Lambda
  -> Spring Boot public API
  -> MapLibre heatmap
```

The initial grid contains roughly 200 major-city and Interstate-corridor
samples. It is interpolated into a lightweight nationwide PNG for immediate
map coverage, but it is not represented as a continuous meteorological
measurement or a safety guarantee.

## HRRR And MRMS Upgrade Worker

`services/weather-raster` is a one-shot container that uses Herbie's HRRR
byte-range selection for temperature and 10-meter wind, combines it with the
latest national MRMS precipitation-rate field, and writes the same
`weather/latest.png` plus `weather/manifest.json` contract. Because the web and
API consume the shared contract, the higher-resolution layer can replace the
interest-grid raster without frontend changes.

The heavy worker is intentionally not scheduled by default. It should first be
run as a measured AWS Batch/Fargate Spot job and enabled only when its measured
monthly cost remains inside the project's five-dollar budget.

Measured locally on June 11, 2026:

- 197 configured city and Interstate samples
- 192 live NOAA/NWS samples
- 97% coverage
- 43.84 seconds to collect
- 12.86 seconds to generate the national raster
- 166.5 KB PNG output
