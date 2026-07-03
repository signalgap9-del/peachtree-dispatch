# AtmosPath 릴리즈 준비 체크리스트

마지막 업데이트: 2026-07-03

이 문서는 AtmosPath를 포트폴리오 데모에서 실제 베타 사용자에게 보여줄 수 있는 수준으로 끌어올리기 위한 운영 체크리스트다. 목표는 “기능이 있어 보이는 화면”이 아니라, 장애/비용/보안/데이터 품질을 설명할 수 있는 서비스다.

## 완료된 기반

- React/Vite 프론트엔드, Spring Boot Platform API, Python Risk Engine 분리
- CloudFront + private S3 정적 호스팅
- API Gateway + Lambda container image 배포 구조
- DynamoDB single-table 기반 saved places / saved routes 저장 구조
- Cognito Hosted UI + PKCE 기반 인증 흐름
- Google OAuth IdP IaC와 프론트엔드 진입점
- CloudFront 배포 URL과 로컬 개발 URL을 Cognito callback/logout URL에 반영
- OSRM route geometry 기반 경로 대안 비교
- NWS active alert snapshot
- NOAA/NWS interest-grid weather snapshot
- MapLibre 기반 전국 risk heatmap / alert polygon / weather point 렌더링
- Home / Dashboard / Alerts / Place outlook의 fake map background 제거
- live/degraded/unavailable 데이터 상태 표시
- CloudFront CSP, HSTS, frame deny, referrer policy, Permissions-Policy
- S3 public access block, versioning, SSE-S3
- API Gateway throttling과 Lambda reserved concurrency
- CloudWatch alarms for DLQ, API errors, worker errors
- Operations dashboard baseline
- Playwright E2E: routing, saved data, auth prompts, accessibility, XSS marker hardening, route alternatives
- HRRR/MRMS raster worker 코드와 optional Lambda/EventBridge/IAM wiring
- 한국어/영어 언어 토글과 정상 한국어 카피

## 아직 릴리즈 블로커

### 1. 실제 Google OAuth smoke test

코드와 IaC는 준비됐지만, 실제 Google OAuth client ID/secret이 GitHub environment secret과 Terraform 변수에 연결되어야 한다.

필요 작업:

- Google Cloud OAuth consent screen production/test user 설정
- Cognito callback URL 등록: `https://<cognito-domain>.auth.us-east-1.amazoncognito.com/oauth2/idpresponse`
- GitHub environment secret 등록: `GOOGLE_OAUTH_CLIENT_ID`, `GOOGLE_OAUTH_CLIENT_SECRET`
- dev 배포 후 Google 로그인으로 토큰 발급 확인
- 실패 시 Cognito Bad Request 대신 앱 내부 안내가 뜨는지 확인

### 2. HRRR/MRMS raster enablement

전국 heatmap은 현재 interest-grid와 NWS alert 기반으로 동작한다. 고해상도 HRRR/MRMS raster worker는 배포 경로가 있지만 비용과 실행 시간을 한 번 측정해야 한다.

필요 작업:

- `ENABLE_HRRR_MRMS_RASTER=true`로 dev 1회 배포
- Lambda cold start, duration, memory, S3 artifact size 측정
- `weather/manifest.json` freshness 확인
- MapLibre raster overlay가 실제 배포에서 보이는지 확인
- 월 $5 목표에 맞는 실행 주기 확정

### 3. 실사용 synthetic monitoring

CloudWatch alarm은 있지만, 사용자가 보는 플로우를 주기적으로 두드리는 synthetic check가 아직 부족하다.

필요 작업:

- `/`, `/map`, `/api/health` synthetic check
- `Miami -> West Palm Beach` route planning synthetic request
- 실패 evidence를 CloudWatch logs 또는 GitHub Actions summary에 남기기
- 알림 수신 채널 결정

### 4. 인증된 saved data live smoke

E2E mock으로는 검증됐지만, 실제 배포에서 Cognito JWT와 DynamoDB 저장이 연결되는지 수동 smoke evidence가 필요하다.

필요 작업:

- 로그인 사용자로 saved place 저장
- saved route 저장
- `/saved` 새로고침 후 데이터 유지 확인
- 테스트 데이터 삭제와 DynamoDB item 확인

### 5. risk scoring governance

Risk score는 explainable baseline이다. 면접/사용자 관점에서 “왜 이 점수인지”를 더 명확히 설명해야 한다.

필요 작업:

- score weight 문서화
- model version UI/API 표시 강화
- stale data / expires-at warning
- unavailable data가 점수를 과신하게 만들지 않는지 QA

### 6. 비용/남용 guardrail

초기 공개 전에 월 $5 목표를 지키는 장치를 명확히 해야 한다.

필요 작업:

- AWS Budget alert 확인
- API Gateway 4xx/5xx dashboard
- Lambda timeout/error dashboard
- CloudFront access log 또는 대체 traffic evidence
- WAF는 고정비 때문에 보류하되, 도입 기준 문서화

## 다음 우선순위

1. Google OAuth secret 연결 후 dev smoke test
2. authenticated saved data live smoke test
3. HRRR/MRMS worker 1회 실행 비용 측정
4. synthetic checks 추가
5. README screenshot과 demo 설명 최신화
6. MapLibre lazy chunk 성능 점검
