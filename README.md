# AtmosPath

[![CI](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/ci.yml/badge.svg)](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/ci.yml)
[![Deploy Dev](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/deploy-dev.yml/badge.svg)](https://github.com/signalgap9-del/peachtree-dispatch/actions/workflows/deploy-dev.yml)

**전국 단위 실시간 기상 위험을 반영해 자동차 경로를 비교하는 AWS 서버리스 내비게이션 플랫폼**

[라이브 서비스](https://d23c97ytqgl4xu.cloudfront.net/) ·
[API Health](https://d23c97ytqgl4xu.cloudfront.net/api/health) ·
[아키텍처 문서](docs/architecture.md) ·
[비용 모델](docs/cost-model.md) ·
[ADR](docs/adr/)

> 포트폴리오 공개 환경은 미국과 대한민국에서만 접속할 수 있습니다.

![실제 배포된 AtmosPath 경로 비교 화면](docs/screenshots/map-route-live.png)

## 프로젝트 소개

AtmosPath는 단순히 가장 빠른 경로를 보여주는 서비스가 아닙니다. 경로상의 강수 확률, 풍속, 온도, NWS 경보를 샘플링하고 위험 점수를 계산해 **빠른 경로와 기상 위험이 낮은 경로를 비교**합니다.

현재 공개 배포에서 확인할 수 있는 기능은 다음과 같습니다.

- 미국 전역 도시·주소 검색
- 자동차, 밴, 트럭 경로 탐색
- OSRM 기반 실제 도로 geometry와 경로 대안
- 경로별 거리, 시간, 기상 지연, 위험 점수 계산
- 경로상의 8개 지점 날씨 샘플과 데이터 커버리지 표시
- NOAA/NWS 기반 전국 197개 도시·고속도로 관심 지점 모니터링
- NWS 경보 geometry와 경로 샘플의 공간 매칭
- MapLibre 기반 전국 지도, 위험 marker, weather layer
- Cognito 인증 사용자별 DynamoDB saved place
- 데스크톱·모바일 반응형 UI

실데이터 공급자가 일시적으로 응답하지 않으면 샘플 경보나 임의 점수를 표시하지 않고 `UNAVAILABLE` 또는 빈 상태를 보여줍니다.

## 실제 화면

| 전국 위험 홈 | 실시간 대시보드 |
| --- | --- |
| ![AtmosPath home](docs/screenshots/home-live.png) | ![AtmosPath dashboard](docs/screenshots/dashboard-live.png) |

| 실제 경로 비교 | 모바일 |
| --- | --- |
| ![AtmosPath live route](docs/screenshots/map-route-live.png) | ![AtmosPath mobile](docs/screenshots/home-mobile-live.png) |

## 시스템 아키텍처

![AtmosPath 시스템 아키텍처](docs/architecture/atmospath-system-architecture.png)

### 요청 흐름

1. React 애플리케이션을 private S3 origin과 CloudFront로 제공합니다.
2. `/api/*` 요청은 CloudFront에서 API Gateway로 전달됩니다.
3. Spring Boot 플랫폼 API가 인증, 사용자 데이터, API 계약을 담당합니다.
4. Python Risk Engine Lambda가 검색, 경로 생성, 기상·경보 위험 계산을 수행합니다.
5. 정기 weather collector가 NOAA/NWS 관심 지점 snapshot을 S3에 저장합니다.
6. DynamoDB는 사용자 saved place와 운영 데이터를 single-table 형태로 저장합니다.

### 현재 배포와 확장 계획

현재 배포:

- Cognito authorization-code + PKCE
- DynamoDB, S3, SQS/DLQ, CloudWatch
- NOAA/NWS, Open-Meteo, OpenStreetMap/Nominatim, OSRM
- 시간당 전국 weather interest-grid 수집

확장 예정:

- Cognito Google social IdP
- HRRR/MRMS 전국 raster ingest 및 tile serving
- PostGIS 기반 대규모 공간 교차 분석

Google OAuth와 HRRR/MRMS는 아키텍처 확장 경로이며, 현재 공개 환경에서 활성화된 기능으로 과장하지 않습니다.

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Frontend | React 19, TypeScript, Vite, MapLibre GL, Playwright |
| Platform API | Java 21, Spring Boot 3, Spring Security, AWS SDK v2 |
| Risk Engine | Python 3.12, FastAPI, Pydantic, OR-Tools |
| Data | DynamoDB, S3 snapshot/raster artifacts, optional PostgreSQL/PostGIS schema |
| AWS | CloudFront, private S3, API Gateway HTTP API, Lambda container images, Cognito, SQS, DLQ, CloudWatch, ECR |
| IaC | Terraform dev/prod modules, remote state, resource tagging |
| CI/CD | GitHub Actions, AWS OIDC, immutable image tags, CloudFront invalidation |
| Security | PKCE, JWT resource server, origin verification header, geo restriction, Trivy, pip-audit, dependency updates |

## 위험 점수 계산

경로마다 OSRM geometry를 최대 8개 지점으로 샘플링합니다.

- 강수 확률
- 풍속
- 극단 고온·저온
- NWS 경보 severity와 event 가중치
- 실제 응답 지점 비율을 이용한 data coverage와 confidence

NWS 경보는 경로 지점마다 외부 API를 호출하지 않습니다. 요청당 전국 alert snapshot을 한 번 읽고 Polygon/MultiPolygon geometry를 로컬에서 매칭해 N+1 네트워크 호출을 제거했습니다.

## 데이터 저장

### DynamoDB

공개 환경의 기본 운영 저장소입니다.

- `PK = USER#{sub}`, `SK = SAVED_PLACE#{id}`: 사용자별 saved place
- 조건부 쓰기와 owner scope
- Lambda/API에서 pay-per-request 사용
- idle compute 비용 없음

### PostgreSQL/PostGIS

고급 공간 질의용 schema와 CI 검증을 포함하지만, 공개 dev 환경에서는 비용 절감을 위해 비활성화되어 있습니다.

- geometry/geography column
- GiST spatial index
- route exposure, hazard intersection 확장 모델

자세한 내용은 [데이터 모델](docs/data-model.md)과 [관계형 모델](docs/relational-data-model.md)을 참고하세요.

## CI/CD

Pull request마다 다음 검사를 실행합니다.

- Python unit/API tests
- Spring Boot Maven tests
- React lint, build, Playwright E2E
- Docker image builds
- Terraform format/validate
- PostgreSQL/PostGIS migration 검증
- Trivy IaC security scan
- `pip-audit` dependency vulnerability scan

`main` 병합 후:

1. GitHub OIDC로 단기 AWS 자격증명을 발급합니다.
2. Risk Engine과 Platform API Lambda 이미지를 빌드해 ECR에 immutable SHA tag로 push합니다.
3. Terraform으로 인프라와 Lambda image URI를 적용합니다.
4. 웹을 빌드해 S3에 업로드하고 CloudFront cache를 무효화합니다.
5. health, Cognito, 실제 Miami → West Palm Beach directions smoke test를 실행합니다.
6. 실패 시 최근 Lambda CloudWatch 로그를 Actions에 출력합니다.

## 비용 설계

소규모 포트폴리오 트래픽에서 월 5달러 이내를 목표로 설계했습니다.

- Lambda/API Gateway/DynamoDB on-demand
- CloudFront + private S3 정적 호스팅
- 시간당 weather collection
- Aurora, ElastiCache, Kubernetes 미사용
- reserved concurrency와 API throttling을 통한 폭주 방지
- 미국·대한민국 CloudFront geo restriction

실제 비용 가정은 [cost model](docs/cost-model.md)에 정리되어 있습니다.

## 로컬 실행

요구 사항: Docker Desktop

```powershell
docker compose up --build
```

- Web: `http://localhost:5173`
- Risk API docs: `http://localhost:8000/docs`
- Platform API health: `http://localhost:8080/health`

자세한 로컬·AWS 구성 매핑은 [local development](docs/local-development.md)을 참고하세요.

## AWS 운영 원칙

- 기본 region: `us-east-1`
- 배포 리소스는 Terraform으로 관리
- 장기 access key를 생성하거나 저장하지 않음
- GitHub Actions OIDC를 routine deployment 경로로 사용
- 로컬 AWS browser login은 break-glass 용도로만 사용
- 삭제 작업은 이 저장소가 관리하는 리소스로 제한

## 현재 한계

- 외부 무료 routing/weather provider의 rate limit과 가용성에 영향을 받습니다.
- NWS alert feed가 일시적으로 실패하면 경보 영역은 명시적으로 unavailable 상태가 됩니다.
- Google social login은 Google OAuth client 발급 후 Cognito IdP 연결이 필요합니다.
- HRRR/MRMS raster worker 코드는 확장 대상으로 유지하며 공개 dev 환경의 상시 pipeline으로는 아직 운영하지 않습니다.

## 추가 문서

- [Architecture](docs/architecture.md)
- [Production risk routing](docs/architecture/production-risk-routing.md)
- [Weather risk pipeline](docs/architecture/weather-risk.md)
- [Deployment](docs/deployment.md)
- [Publish readiness](docs/publish-readiness.md)
- [Roadmap](docs/roadmap.md)
- [Google social login setup](docs/google-auth.md)
- [Runbooks](docs/runbooks/)
