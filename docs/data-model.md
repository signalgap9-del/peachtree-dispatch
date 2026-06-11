# DynamoDB Operational Data Model

## Ownership Boundary

DynamoDB owns short-lived, key-addressable operational state for weather
ingestion and risk processing. It does not own AtmosPath users, saved items,
route history, or spatial relationships; PostgreSQL/PostGIS owns those records.

## Access Patterns

| ID | Operation | Key strategy |
| --- | --- | --- |
| AP1 | Get the current snapshot metadata for a product/layer | `PK=SNAPSHOT#<product>`, `SK=CURRENT` |
| AP2 | Get one ingestion or raster-generation job | `PK=JOB#<jobId>`, `SK=META` |
| AP3 | List recent job events | `PK=JOB#<jobId>`, `SK=EVENT#<timestamp>#<eventId>` |
| AP4 | Claim an idempotent request exactly once | `PK=IDEMPOTENCY#<scope>#<key>`, `SK=LOCK` |
| AP5 | Read a short-lived computed risk result | `PK=CACHE#RISK#<cacheKey>`, `SK=RESULT` |
| AP6 | Deduplicate one alert notification | `PK=NOTIFY#<subscriptionId>`, `SK=<eventFingerprint>` |
| AP7 | Find failed asynchronous work | SQS DLQ, not a table scan |

## Representative Items

```json
{
  "PK": "SNAPSHOT#HRRR-SURFACE-RISK",
  "SK": "CURRENT",
  "entityType": "SnapshotPointer",
  "objectKey": "weather/hrrr/2026-06-11T12:00:00Z/risk.pmtiles",
  "generatedAt": "2026-06-11T12:12:00Z",
  "expiresAt": 1781172720,
  "sourceStatus": {"hrrr": "LIVE", "mrms": "LIVE"}
}
```

```json
{
  "PK": "JOB#01J...",
  "SK": "META",
  "entityType": "RasterGenerationJob",
  "status": "RUNNING",
  "sourceRun": "hrrr-20260611-12",
  "attempt": 1,
  "createdAt": "2026-06-11T12:05:00Z",
  "expiresAt": 1783761900
}
```

## Indexes and Retention

The current deployed table contains legacy delivery-oriented GSIs from the
earlier prototype. They are not part of the target AtmosPath model and will be
removed only through a separately reviewed, non-destructive migration.

- TTL removes idempotency locks, caches, job history, and notification dedupe records.
- Large weather objects and rasters live in S3, never DynamoDB.
- Job failures and replay are handled through SQS/DLQ.
- Durable user and spatial data lives in PostgreSQL/PostGIS.
