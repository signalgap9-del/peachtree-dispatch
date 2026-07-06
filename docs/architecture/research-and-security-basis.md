# Research and Security Basis

Date: 2026-07-06

This document records the external architecture and research references used to
shape the AtmosPath demo-to-production path.

## Serverless SaaS Architecture

Reference: [AWS Well-Architected Serverless Applications Lens](https://docs.aws.amazon.com/wellarchitected/latest/serverless-applications-lens/welcome.html)

AtmosPath uses a serverless-first deployment because the portfolio preview must
stay inexpensive while still looking like a production system:

- CloudFront for HTTPS, global caching, geo restriction, and security headers.
- Private S3 for the static React/Vite application.
- API Gateway and Lambda for the API boundary.
- Cognito for managed user authentication.
- DynamoDB for low-idle-cost saved routes, saved places, and usage counters.
- S3 for generated weather snapshot and raster artifacts.

This avoids always-on Kubernetes, Aurora, and Redis cost until traffic or data
requirements justify them.

## Application Security

Reference: [OWASP Application Security Verification Standard](https://owasp.org/www-project-application-security-verification-standard/)

The current hardening work maps to common ASVS-style verification themes:

- authenticated private data access;
- owner-scoped persistence;
- structured error contracts without secret leakage;
- request correlation IDs;
- security response headers;
- origin verification between CloudFront and the API origin;
- outbound request allowlisting and local/private-network blocking;
- safe ML artifact loading using JSON coefficients instead of executable model
  serialization formats.

## Stochastic Vehicle Routing Research

Reference: [SVRPBench](https://arxiv.org/abs/2505.21887)

SVRPBench is a useful north star for the route optimizer because it frames the
hard version of the problem: stochastic travel time, time-dependent congestion,
probabilistic incidents, time windows, multi-depot routing, and multi-vehicle
dispatch. AtmosPath does not implement the full benchmark yet. It uses that
direction to keep the API shape honest:

- edge-level risk features;
- saved-route observations as future labels;
- shadow prediction before serving;
- capped and confidence-gated ML route-cost application;
- future benchmark harness before broader ML rollout.

## VRP Solver Direction

Reference: [PyVRP documentation](https://pyvrp.org/)

The current serving path uses OR-Tools as a stable baseline. PyVRP is documented
as the next candidate when we need stronger production constraints:

- time windows;
- service durations;
- heterogeneous fleets;
- vehicle shift limits;
- maximum distance and duration constraints;
- optional clients and penalties.

The route-engine API already exposes enough concepts to add a PyVRP adapter
without rewriting the product surface.

## Operational Data Feeds

References:

- [National Weather Service API](https://www.weather.gov/documentation/services-web-api)
- [USDOT WZDx](https://www.transportation.gov/av/data/wzdx)

AtmosPath should prefer official or operational feeds over scraped content:

- NWS alerts for authoritative active hazards.
- NOAA/NWS forecast point data for live weather risk snapshots.
- WZDx feed registry discovery for roadwork, closures, detours, and work zones.
- State 511 feeds as the next road-incident integration layer.
- HRRR/MRMS raster artifacts after cost and cadence are measured.

## Current Production Boundary

Production-shaped today:

- route comparison and risk explanation;
- saved route/place ownership model;
- guarded ML workflow;
- local stress harness;
- deployable AWS IaC;
- cost-aware serverless topology.

Still gated before broad public release:

- Google OAuth deployed smoke test;
- WAF or equivalent managed edge protections if traffic becomes public;
- async optimization job execution for larger VRP requests;
- synthetic monitoring and alerting on key flows;
- real edge-observation persistence from saved-route check-ins;
- NWS/WZDx/511 joins into edge-level route risk.
