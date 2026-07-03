# AtmosPath Release Readiness Checklist

이 문서는 포트폴리오 데모를 넘어 실제 사용자를 받기 전에 남은 핵심 리스크를 추적한다. 기준은 “보기에 그럴듯함”이 아니라 운영, 데이터, 인증, 비용, 회귀 방지까지 포함한 beta release 준비 상태다.

## 완료된 기반

- React/Vite frontend, Spring Boot Platform API, Python Risk Engine 분리
- CloudFront + private S3 static hosting
- API Gateway + Lambda container images
- DynamoDB single-table operational store
- Cognito/JWT 기반 사용자 소유 데이터 보호 구조
- saved places 및 saved routes 계정 저장/조회/삭제 API
- OSRM route geometry 기반 경로 대안 비교
- NWS alert snapshot과 NOAA/NWS interest-grid weather snapshot
- MapLibre weather/risk layer와 live/degraded data state
- CloudFront CSP, HSTS, frame deny, referrer policy, Permissions-Policy
- S3 public access block, versioning, SSE-S3
- API Gateway throttling과 Lambda reserved concurrency
- CloudWatch alarms for DLQ, API errors, worker errors
- Operations dashboard baseline
- Playwright E2E for routing, saved data, auth prompts, accessibility, and XSS marker hardening
- Spring Boot Maven tests running locally and in CI
- Local Windows JDK/Maven bootstrap scripts
- HRRR/MRMS raster worker code
- HRRR/MRMS raster worker ECR, optional Lambda, EventBridge schedule, log group, and alarm wiring

## 아직 Release Blocker인 것

### 1. Real Google OAuth

현재 Google 버튼과 Cognito IdP IaC는 준비되어 있지만, 실제 Google OAuth client ID/secret, consent screen, redirect URI 검증이 필요하다.

남은 액션:

- Google Cloud OAuth client 생성
- Cognito callback URL 등록
- GitHub environment secrets 설정
- dev 배포 후 Google login smoke test
- 실패 시 Bad Request가 아니라 사용자에게 명확한 setup/error state 표시 확인

### 2. HRRR/MRMS Enablement

고해상도 raster worker는 이제 opt-in 배포 경로가 생겼다. 다만 실제 스케줄 enable은 비용과 실행 시간을 한 번 측정한 뒤 켜야 한다.

남은 액션:

- `ENABLE_HRRR_MRMS_RASTER=true`로 dev 1회 배포
- Lambda cold start, duration, memory, S3 artifact size 측정
- `weather/manifest.json` freshness와 MapLibre raster overlay 확인
- 실패 시 low-cost interest-grid raster fallback 확인
- 월 $5 목표 내 실행 주기 확정

### 3. Synthetic Monitoring

CloudWatch alarms는 있지만 외부 사용자가 보는 흐름을 주기적으로 검증하는 synthetic canary는 아직 없다.

남은 액션:

- `/`, `/map`, `/api/health` synthetic check
- `Miami -> West Palm Beach` route planning synthetic request
- failure evidence를 CloudWatch logs 또는 GitHub summary에 남기기
- 알람 수신 채널 결정

### 4. Authenticated Saved Data In Production

코드와 E2E는 통과하지만, live 배포에서 Cognito JWT와 DynamoDB saved routes/places를 실제로 저장해보는 smoke evidence가 필요하다.

남은 액션:

- dev 배포 후 로그인 사용자로 place 저장
- route 저장
- `/saved` 새로고침 후 유지 확인
- 삭제 후 DynamoDB item 제거 확인

### 5. Data Governance And Scoring

risk score는 explainable baseline이지만, 사용자에게 “정확한 예보”처럼 보이지 않도록 모델 버전과 한계를 명확히 해야 한다.

남은 액션:

- score weights 문서화
- model version UI/API 표시 강화
- stale data indicator와 expires-at warning
- unavailable data가 risk score를 과신하게 만들지 않는지 QA

### 6. Production Abuse And Cost Guardrails

현재 API throttling과 reserved concurrency는 있지만, beta 공개 전에 abuse/cost guard를 더 명확히 해야 한다.

남은 액션:

- AWS Budget alert 확인
- API Gateway 4xx/5xx dashboard
- Lambda timeout/error dashboard
- CloudFront access 로그 또는 대체 traffic evidence
- WAF는 고정비 때문에 보류하되, 도입 기준 문서화

## 다음 우선순위

1. Dev에 latest branch 배포해서 saved routes API와 HRRR/MRMS opt-in path가 Terraform apply에서 깨지지 않는지 확인
2. Google OAuth secret 연결
3. authenticated saved data live smoke test
4. HRRR/MRMS worker 1회 실행 비용 측정
5. synthetic checks 추가
6. README screenshots와 demo video 최신화
