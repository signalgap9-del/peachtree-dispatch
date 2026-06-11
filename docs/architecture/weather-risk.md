# Weather Risk Architecture

## Product Model

Peachtree Routes separates weather risk into three horizons:

1. **Active hazards:** current NWS watches, warnings, and advisories.
2. **Near-term driving conditions:** precipitation probability, wind, heat, and
   endpoint weather from Open-Meteo.
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
- Open-Meteo: no-key development provider for current portfolio traffic.
- FEMA NRI/NFHL: planned static ingestion into low-cost object storage or
  pre-generated map tiles.
- No always-on paid weather service is required for the portfolio deployment.
