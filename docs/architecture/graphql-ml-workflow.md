# GraphQL and ML Workflow Direction

Date: 2026-07-04

## Why this exists

AtmosPath is moving from separate route, alert, road-event, saved-route, and VRP
REST calls toward composed product workflows. The frontend will soon need one
screen to ask for a saved route, current risk, NWS alert overlaps, WZDx/511 road
events, route alternatives, and ML shadow-scoring status. A typed GraphQL BFF is
the right place to compose those reads without replacing the stable REST
endpoints that already work.

## Source-backed decisions

| Source | Decision |
| --- | --- |
| FastAPI GraphQL guide, https://fastapi.tiangolo.com/how-to/graphql/ | Use Strawberry for the FastAPI risk engine because it follows FastAPI's type-annotation style. |
| Strawberry FastAPI integration, https://strawberry.rocks/docs/integrations/fastapi | Mount `GraphQLRouter` at `/graphql` and keep existing FastAPI/Pydantic validation behind resolvers. |
| GraphQL over HTTP, https://graphql.org/learn/serving-over-http/ | Serve GraphQL over HTTP `POST /graphql`; disable GET queries to keep request semantics predictable. |
| Spring for GraphQL, https://docs.spring.io/spring-graphql/reference/index.html | Keep Spring GraphQL as a later option. This slice proxies to the Python BFF to avoid maintaining two schemas. |
| AWS AppSync docs, https://docs.aws.amazon.com/appsync/ | Treat AppSync as a future managed GraphQL option when real-time subscriptions or multi-source AWS resolver composition justify it. |

## Current implementation

- `POST /graphql` on the FastAPI risk engine.
- `POST /api/v1/graphql` through the Spring platform API proxy.
- Lambda internal risk-engine mode can execute the same GraphQL schema.
- Saved route observations can be persisted through the Spring platform API:
  - `POST /api/v1/me/saved/routes/{savedItemId}/observations`
  - `GET /api/v1/me/saved/routes/{savedItemId}/observations`
  - `GET /api/v1/me/saved/routes/{savedItemId}/ml-dataset`
- GraphQL mutations:
  - `planMultiStopRoute(input: MultiStopRouteInput!)`
  - `optimizeMultiStopRoute(input: MultiStopRouteInput!)`
  - `solveVrp(input: VrpScenarioInput!)`
- GraphQL queries:
  - `routeEngineCapabilities`
  - `mlWorkflowStatus`
- GraphQL IDE is disabled by default and can be enabled locally with
  `GRAPHQL_IDE_ENABLED=true`.

## ML workflow posture

The ML workflow is deliberately shadow-first:

1. Rule-based route risk remains authoritative.
2. `edge-cost-v1` feature vectors define the route-engine cost contract.
3. `saved-route-observation-v1` records route-level labels: planned duration,
   actual duration, delay label, observed risk, encountered hazards, weather
   summary, and road-event summary.
4. Shadow predictions must not affect served cost until offline backtests pass.
5. Weather joins, road-event joins, and backtest gates must
   be implemented before a model can serve users.

## Estimated LOC by track

| Track | Expected LOC |
| --- | ---: |
| GraphQL BFF schema, resolvers, proxy, tests | 700-1,200 |
| ML feature vectors, shadow model, workflow status, tests | 1,500-3,000 |
| Route observation persistence and dataset exporter | 2,000-4,000 |
| Offline trainer/backtest harness | 3,000-6,000 |
| Frontend GraphQL dashboard and saved-route composition | 2,000-4,000 |
| Observability, security controls, release gates | 1,500-3,000 |

Near-term realistic expansion is 10k-20k LOC. A 50k LOC codebase is plausible
only after saved-route monitoring, road-event ingestion, alert search,
production observability, ML offline training, and integration tests are all
implemented. LOC should be a byproduct of product depth, not padding.

## Next slices

1. Add `GET /road-events` normalized events from no-key WZDx/511 feeds.
2. Add GraphQL query composition for saved route detail:
   saved route + current risk + alert overlap + road events + risk history.
3. Add offline dataset export beyond per-route rows and baseline trainer.
4. Add backtest thresholds and shadow-model release gates.
