# Local Development

## Quick Start

```powershell
./scripts/local-up.ps1
```

This runs `docker compose up --build -d` and starts all services:

| Service | URL | Description |
| --- | --- | --- |
| Web (React + Vite + MapLibre) | `http://localhost:5173` | Map-first SPA |
| Platform API (Spring Boot) | `http://localhost:8080` | Public API boundary |
| Risk Engine (FastAPI) | `http://localhost:8000` | Geospatial scoring and routing |
| Risk Engine docs | `http://localhost:8000/docs` | OpenAPI reference |

Stop the environment:

```powershell
./scripts/local-down.ps1
```

## Architecture Mapping

| Local Component | AWS Target |
| --- | --- |
| React + Vite + MapLibre SPA (Docker dev server) | S3 private origin + CloudFront |
| Spring Boot platform API (in-memory stores) | ECS Fargate + DynamoDB + Cognito |
| FastAPI risk engine (in-memory/DynamoDB adapter) | Lambda or Fargate behind API Gateway |
| OSRM public routing | Swappable routing provider adapter |
| NWS/NOAA + Open-Meteo provider adapters | Cached ingestion via EventBridge schedule |
| OR-Tools VRP solver (in-process) | On-demand Fargate task or Lambda |
| In-process weather snapshot refresh | SQS-triggered raster/risk worker |
| Optional PostGIS Compose profile (`relational`) | Aurora PostgreSQL Serverless v2 + PostGIS |
| Docker Compose orchestration | GitHub Actions + Terraform deployment |

## Local Storage Adapters

The platform API uses in-memory adapters by default for saved places, usage
tracking, idempotency, and rate limiting. Set `DYNAMODB_TABLE` to switch to a
DynamoDB-backed repository (point `DYNAMODB_ENDPOINT_URL` at DynamoDB Local if
needed).

The Python risk engine selects its repository through `DYNAMODB_TABLE` as well;
without it, a lightweight SQLite fallback is used strictly for local
development convenience.

## Optional PostGIS Profile

Start only the relational database and apply the versioned schema:

```powershell
docker compose --profile relational up -d postgres
```

This profile does not affect the default application runtime. It exists to
inspect and exercise the relational/spatial schema locally.

## Testing

Python risk engine:

```powershell
cd services/api
$env:PYTHONPATH = "."
python -m pytest -q
```

Spring Boot platform API:

```powershell
./scripts/mvn.ps1 -f services/platform-api/pom.xml test
```

Frontend:

```powershell
cd web
npm run lint
npm run build
```

Full stack smoke test:

```powershell
docker compose build
docker compose up -d
docker compose ps
```
