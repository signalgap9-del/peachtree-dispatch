# Local Development

## Purpose

The local environment makes the product and domain model reviewable before AWS infrastructure is created.

```powershell
./scripts/local-up.ps1
```

- Climate routing map: `http://localhost:5173`
- OpenAPI documentation: `http://localhost:8000/docs`
- Health endpoint: `http://localhost:8000/health`

Stop the environment:

```powershell
./scripts/local-down.ps1
```

## Architecture Mapping

| Local Component | AWS Target |
| --- | --- |
| React, Vite, and MapLibre map client | S3 private origin and CloudFront |
| FastAPI container | API Gateway and Lambda adapter |
| SQLite repository | DynamoDB repository adapter |
| Optional PostGIS Compose profile | Aurora PostgreSQL Serverless v2 + RDS Data API |
| Climate-aware route ranking | Versioned risk/optimization Lambda or on-demand Fargate task |
| Open-Meteo forecast integration | Cached forecast ingestion through EventBridge schedule |
| OSRM public route geometry | Swappable routing provider adapter |
| In-process provider refresh | SQS-triggered weather/risk worker |
| Docker Compose | GitHub Actions deployment workflows |

The route and risk contracts, API request models, idempotency behavior, live
weather risk layer, route ranking, road geometry, and map-first user experience
are implemented locally. AWS adapters remain separate work and should be
introduced through reviewed Terraform plans.

Start only the local PostGIS database and apply the versioned schema:

```powershell
docker compose --profile relational up -d postgres
```

This profile is optional and does not change the default local application
runtime. It exists to inspect and exercise the relational/spatial schema.

## Verification

```powershell
cd services/api
python -m pytest -q

cd ../../web
npm run lint
npm run build

cd ..
docker compose build
docker compose up -d
docker compose ps
```
