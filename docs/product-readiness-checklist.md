# AtmosPath 상용앱 대비 체크리스트

이 문서는 현재 포트폴리오 배포를 실제 상용 앱 기준으로 끌어올리기 위한 점검표다.

## 지금 갖춘 것

- React/Vite 프론트엔드, Spring Boot platform API, Python risk engine 분리
- AWS CloudFront, Lambda, DynamoDB, ECR, Terraform 기반 배포
- live NWS/NOAA 기반 national risk, weather snapshot, route risk API
- 지도 기반 route alternatives, weather/risk layer, saved places, alerts 화면
- Google OAuth 진입점, Cognito 연동 구조, 이메일 로그인 fallback
- Playwright E2E로 핵심 사용자 플로우와 degraded 상태 검증
- GitHub Actions CI, Terraform validate/plan, container build, security scan

## 상용앱 대비 부족한 것

- Google OAuth 운영 설정: Google OAuth client ID/secret, redirect URI, consent screen 검증 필요
- 계정 UX: 로그인 성공/실패 화면, logout UX, 계정 메뉴, 세션 만료 처리 보강 필요
- 실데이터 품질: HRRR/MRMS raster pipeline의 운영 스케줄, freshness SLA, stale data 표시 필요
- 지도 검색: public geocoder fallback은 있지만 provider SLA와 rate-limit 대응이 부족함
- 지도 UX: national outlook은 개선됐지만 full MapLibre 화면과 같은 정확한 geometry 표현은 아님
- Risk model governance: score weights, versioning, calibration, explainability 문서와 테스트 부족
- Saved UX: 장소 추가/삭제/route 저장의 서버 동기화와 optimistic UI 부족
- Observability: CloudWatch dashboard, structured logs, alarms, synthetic checks, error budget 미완성
- Security hardening: WAF/rate limiting, CSP, security headers, abuse prevention, secret rotation 점검 필요
- Accessibility: keyboard navigation, focus order, contrast, screen-reader QA가 자동화 수준까지는 부족함
- Performance: map chunk는 lazy-loaded지만 MapLibre 자체 용량이 크고 route-level performance budget이 없음
- Product polish: loading skeleton, empty states, onboarding, pricing/cost safety messaging, support/contact flow 부족
- Release process: staging/prod promotion checklist, rollback drill, smoke test evidence 자동 첨부 필요
- Data compliance: privacy policy, data retention, user deletion/export policy 필요

## 다음 우선순위

1. Google OAuth 실제 secret 설정 후 로그인 smoke test를 live 배포에서 통과시키기
2. HRRR/MRMS raster freshness와 stale-data indicator를 UI/API에 추가하기
3. saved place 추가/삭제/route 저장을 platform API와 완전히 연결하기
4. CloudWatch alarm, API latency/error dashboard, synthetic canary 추가하기
5. accessibility와 performance budget을 CI gate에 추가하기
6. public README에 live demo, architecture, risk model, cost-control story를 더 명확히 쓰기
