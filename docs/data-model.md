# DynamoDB Operational Data Model

## Ownership Boundary

DynamoDB owns key-addressable operational state for weather ingestion and risk
processing and the low-volume authenticated saved-place workflow. Optional
PostgreSQL/PostGIS remains the future path for complex spatial relationships.

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
| AP8 | List one user's saved places | `PK=USER#<userId>`, `SK begins_with SAVED_PLACE#` |
| AP9 | Create or delete one saved place | `PK=USER#<userId>`, `SK=SAVED_PLACE#<savedItemId>` |
| AP10 | List one user's saved routes | `PK=USER#<userId>`, `SK begins_with SAVED_ROUTE#` |
| AP11 | Record/list saved-route observations | `PK=USER#<userId>`, `SK begins_with ROUTE_OBSERVATION#<savedItemId>#` |

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
- Saved places live in DynamoDB for the low-traffic preview.
- Saved route observations are small, user-owned label records and can live in
  the same single-table partition during preview.
- Complex route-history and spatial relationship queries belong in optional PostgreSQL/PostGIS.
