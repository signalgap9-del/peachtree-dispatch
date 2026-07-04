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
| AP10 | Increment one tenant's daily metered usage | `PK=TENANT#<tenantId>`, `SK=USAGE#<yyyy-MM-dd>#<feature>` with atomic `ADD used :one` |

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

```json
{
  "PK": "TENANT#9b9a...",
  "SK": "USAGE#2026-07-04#ROUTE_PLAN",
  "entityType": "TenantUsageCounter",
  "tenantId": "9b9a...",
  "feature": "ROUTE_PLAN",
  "day": "2026-07-04",
  "used": 8,
  "updatedAt": "2026-07-04T12:05:00Z",
  "expiresAt": 1785801600
}
```

## Indexes and Retention

The current deployed table contains legacy delivery-oriented GSIs from the
earlier prototype. They are not part of the target AtmosPath model and will be
removed only through a separately reviewed, non-destructive migration.

- TTL removes idempotency locks, caches, job history, and notification dedupe records.
- Large weather objects and rasters live in S3, never DynamoDB.
- Job failures and replay are handled through SQS/DLQ.
- Saved places, saved routes, and tenant usage counters live in DynamoDB for the low-traffic preview.
- Usage counters use tenant-scoped partition keys and atomic update expressions; they are not stored in process memory in AWS deployments.
- Complex route-history and spatial relationship queries belong in optional PostgreSQL/PostGIS.
