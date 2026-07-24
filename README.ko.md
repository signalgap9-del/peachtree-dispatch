# AtmosPath

**"지금 이 구간 지나가면, 어느 루트가 날씨·도로 위험을 가장 덜 타는가?"**

보통 내비게이션은 이 질문에 답하지 않거든요. AtmosPath는 여기서 출발했습니다. 소요시간만 보는 게 아니라, 실시간 기상 데이터와 NWS 공식 경보, 도로 이벤트까지 겹쳐서 경로 대안을 비교해 줍니다. 구간마다 "여기가 왜 위험한지" 설명이 붙고, 자주 쓰는 경로는 워치리스트에 저장해서 위험도를 계속 추적할 수 있어요. SaaS처럼 플랜별 쿼터와 사용량 관리도 돌아가고, 기본 지도 기능은 로그인 없이 바로 쓸 수 있습니다.

[라이브 미리보기](https://d23c97ytqgl4xu.cloudfront.net/) | [API 헬스체크](https://d23c97ytqgl4xu.cloudfront.net/api/health) | [데모 플레이북](docs/demo-playbook.md) | [변경 이력](CHANGELOG.md)

**[English](README.md)** | 한국어

![AtmosPath 경로 비교 화면](docs/screenshots/map-route-live.png)

---

## 이 프로젝트가 하는 일

**경로 위험 비교.** 미국 내 출발지·도착지를 넣으면 세 가지 대안이 나옵니다. 가장 빠른 길, 기상 위험이 낮은 길, 그리고 그 둘의 균형. 각 경로에 구간별 리스크 설명이 붙으니까, "왜 이 코리가 더 위험한지"를 눈으로 확인할 수 있어요.

**실시간 위험 정보.** 전국 기상 위험 전망, 지점별 리스크 점수, 위험 유형이나 도시·카운티·코리도어 기준으로 경보를 검색할 수 있습니다. 데이터 소스는 NWS와 NOAA이고, 소스가 내려가면 가짜 수치를 채워 넣는 대신 "현재 이용 불가"라고 솔직하게 표시합니다.

**경로 워치리스트.** 로그인한 사용자는 경로를 저장하고, 위험 임계값을 걸고, 모니터링을 켜고, 리스크를 직접 갱신하고, 과거 위험 이력을 볼 수 있어요. 저장 데이터는 전부 DynamoDB에서 소유자 범위 조건부 쓰기로 관리하니까 남의 데이터를 건드릴 수 없습니다.

**SaaS 계정 레이어.** 플랜(FREE/PRO/TEAM/INTERNAL)별로 일일 사용량, 저장 경로·장소 용량, 구조화된 쿼터 에러, 멱등성 키 기반 안전 재시도까지 갖춰져 있습니다. 결제는 일부러 안 붙였어요. 권한 경계부터 제대로 잡아두면 나중에 결제 연동이 깔끔하게 들어가니까요.

**다중 경유지 최적화.** OR-Tools 위에 리스크 가중 엣지 코스트를 올린 VRP 기반이고, ML 섀도 워크플로우는 프로모션 게이트를 전부 통과해야만 솔버 코스트에 반영됩니다.

**운영 상태 투명하게 공개.** `/status` 페이지에 데이터 소스 생존 여부, 프론트엔드 재시도·폴백 횟수, 브라우저 성능 스냅샷, 세션 범위 에러 로그가 다 보입니다. 서드파티 분석 도구 안 씁니다.

---

## 아키텍처

```mermaid
flowchart LR
  User["브라우저"] --> CF["CloudFront<br/>지역 제한, 보안 헤더"]
  CF --> S3["Private S3<br/>React/Vite SPA"]
  CF --> APIGW["API Gateway<br/>/api/*"]
  APIGW --> Spring["Spring Boot 3.5<br/>인증, 테넌트, 쿼터, 저장 데이터"]
  Spring --> Cognito["Cognito / Google OAuth"]
  Spring --> DDB["DynamoDB<br/>싱글 테이블"]
  Spring --> Risk["FastAPI 리스크 엔진<br/>라우팅, 기상, 경보, VRP"]
  Risk --> NWS["NWS / NOAA"]
  Risk --> WZDX["USDOT WZDx / 511 피드"]
  Risk --> S3Data["S3 기상 아티팩트"]
```

전체 그림은 이렇습니다. React 19 SPA를 Private S3에 올리고 CloudFront로 서빙하고, API 호출은 API Gateway를 타서 백엔드 두 개로 갑니다. Spring Boot가 인증·테넌트·권한·사용자 데이터 저장을 맡고, FastAPI가 라우팅·기상 스코어링·경보 집계·VRP 최적화를 맡아요. 운영 스토어는 DynamoDB(유휴 비용 0)이고, 공간 조인이 필요해지면 PostGIS로 확장하는 경로를 ADR로 문서화해 뒀습니다.

---

## 어떻게 만들었나

면접에서 "이거 왜 이렇게 만들었어요?"라는 질문이 들어왔을 때, 바로 답할 수 있는 내용들입니다.

### 리스크 점수: 왜 단순 평균이 아닌가

처음엔 카테고리 점수를 그냥 평균냈는데, 토네이도 경보 하나가 떠 있어도 "전체적으로 맑음"이면 점수가 낮게 나오는 문제가 있었어요. 그래서 설계를 바꿨습니다.

```
score = max(
    alert_score,
    precipitation * 0.35 + wind * 0.25 + heat * 0.20 + flood * 0.20
)
```

`max`을 취하면 심각한 경보 하나가 전체 점수를 지배합니다. 반대로 경보는 없는데 비+바람+더위가 겹치는 경우는 가중 합성이 잡아주고요. 전국 리스크는 상위 20개 경보만 평균내는데, 수백 개 저심각도 자문문이 점수를 희석하는 걸 막기 위해서입니다.

전국 엔드포인트에는 60초 TTL 인메모리 캐시를 걸었어요. Redis를 띄우면 월 비용이 나가는데, 프리뷰 단계에선 그 정도 캐싱이면 충분하거든요.

### 경로 대안 비교: 라벨이 붙는 과정

OSRM에 `alternatives=3`을 요청하면 기하학적으로 서로 다른 경로가 옵니다. 각 후보 경로에서 웨이포인트를 찍어 기상을 샘플링하고, NWS 경보 지오메트리와 교차하는지 확인해서 점수를 매겨요. 그 다음 소요시간순으로 정렬하고:

- **Fastest**: 리스크 상관없이 가장 빠른 길
- **Lower weather risk**: 가장 빠른 길이 아닌 것 중 리스크 점수가 가장 낮은 길
- **Balanced**: 시간 + 리스크 트레이드오프가 가장 좋은 길

구간별 분해도 붙입니다. 경로를 기상 샘플 간격으로 쪼개서, "이 구간에서 홍수 위험이 높다" 같은 설명을 UI에서 보여줄 수 있게요.

### SSRF 차단: 외부 API 호출을 어떻게 안전하게 하나

리스크 엔진이 NWS, Open-Meteo, OSRM, WZDx 같은 외부 API를 호출하는데, 여기서 임의 URL을 허용하면 SSRF 공격면이 생깁니다. 그래서 `outbound_http.py`에 게이트를 하나 두고 모든 아웃바운드 요청이 여기를 통과하게 했어요:

1. **호스트 허용 목록.** api.weather.gov, router.project-osrm.org 등 사전 승인 도메인만 통과. 환경변수로 명시적 추가는 가능.
2. **HTTPS만.** 공개 호스트에 평문 HTTP 요청은 즉시 거부.
3. **리다이렉트 차단.** 커스텀 `HTTPRedirectHandler`가 리다이렉트 시 예외를 던집니다. 오픈 리다이렉트로 허용 목록을 우회하는 공격을 막으려고요.
4. **DNS 해석 검증.** 연결 전에 호스트네임을 실제로 해석해서 loopback, link-local, private, reserved, multicast에 걸리면 차단. DNS 리바인딩으로 `169.254.169.254`(클라우드 메타데이터)를 찌르는 공격을 막는 핵심 장치입니다.
5. **자격 증명 제거.** URL에 `user:pass@`가 박혀 있으면 거부.

로컬 개발할 때 localhost 접근이 필요하면 `ATMOSPATH_ALLOW_LOCAL_OUTBOUND=true`를 명시적으로 켜야 합니다.

### 레이트 리밋: 버켓 분류와 비용 절감

Spring 쪽에 서블릿 필터(`RateLimitFilter`)를 두고, 메서드 + 경로 패턴으로 요청을 버켓에 분류합니다:

| 버켓 | 대상 | 제한 |
| --- | --- | --- |
| `public-risk-read` | GET /risk/national, weather-snapshot, weather-raster | 분당 설정값 |
| `place-search` | GET /places/search | 뮤테이션 티어 |
| `route-risk-mutation` | POST /directions, /risk/location | 뮤테이션 티어 |
| `authenticated-me` | /me/** | 인증 사용자 별도 제한 |

키는 `bucket:method:path:clientIP` 조합입니다. 기본 스토어는 인메모리 고정 윈도우인데, 프리뷰 단계에서 Redis를 띄울 이유가 없거든요. 멀티 인스턴스로 갈 때 `RATE_LIMIT_STORE=redis`만 바꾸면 됩니다. 모든 판정은 Micrometer 카운터(`atmospath.rate_limit.requests`)로 나가고, 응답에는 `X-RateLimit-*` 헤더와 `Retry-After`가 포함된 구조화된 429 본문을 실어 보냅니다.

### 멱등성: 네트워크 끊겨도 유령 데이터가 안 생기게

경로 저장, 장소 저장 같은 뮤테이션에 `Idempotency-Key` 헤더를 받습니다. 구현 포인트:

1. 키 형식 검증 (1~128자, `[A-Za-z0-9._:-]`만 허용).
2. **저장 전에 SHA-256으로 해싱.** 원본 클라이언트 키가 DynamoDB에 그대로 들어가지 않습니다.
3. `tenantId + operation` 범위로 저장하니까 테넌트 간 키 충돌이 원천적으로 안 생겨요.
4. 같은 키로 재시도하면 새로 만들지 않고 기존 리소스 ID를 돌려줍니다.

프론트에서 네트워크 끊겨서 재시도해도 저장 경로가 중복으로 생기지 않아요. 멱등성 히트는 `atmospath.saved_route.commands` 메트릭으로 추적하고 있습니다.

### 서비스 레이어: 컨트롤러는 얇게, 순서는 중요하게

Platform API 컨트롤러는 요청 파싱 → 테넌트 컨텍스트 추출 → 서비스 위임, 이 세 가지만 합니다. 비즈니스 로직은 전부 `SavedRouteService`에 모여 있고, 이 순서로 처리해요:

1. 멱등성 체크 (이미 처리된 키면 기존 리소스 반환)
2. **쿼터 체크** (플랜 용량 넘으면 여기서 거부)
3. 도메인 객체 생성 + DynamoDB 저장
4. 리스크 관측 기록 (나중에 ML 학습 데이터로 쓸 수 있게)
5. 멱등성 키 저장
6. 커맨드 메트릭 발행

순서가 중요한 이유: 쿼터 체크가 저장 *앞*에 있으니까, 거부된 요청은 DynamoDB를 아예 건드리지 않습니다. 그리고 이 서비스는 `@ConditionalOnProperty(atmospath.auth.enabled=true)`라서, Cognito 없는 로컬 개발에서도 문제없이 돌아갑니다.

### ML 서빙: 게이트 4개 다 통과해야 반영

VRP 지연 모델이 솔버 코스트에 영향을 줄 수 있게는 해뒀는데, 기본적으로는 섀도 모드입니다. 룰 기반 점수가 권위이고, ML이 실제로 코스트를 바꾸려면 게이트 4개를 전부 넘어야 해요:

1. **학습.** 모델 학습 + 평균 지연 baseline 대비 백테스트. MAE가 baseline보다 나아져야 릴리스 게이트 통과.
2. **프로모션.** CLI로 `served_to_users=true` 아티팩트를 새로 생성. 섀도 아티팩트는 계속 `false`.
3. **런타임.** 환경변수에 `VRP_ML_WORKFLOW_MODE=SERVING_ENABLED` + `VRP_ML_ALLOW_SERVED_COST=true`가 있어야 함.
4. **요청.** 개별 요청에서 `useMlServedCost=true`를 명시적으로 보내야 함.

하나라도 빠지면 룰 기반 코스트로 fail-closed. 반영되는 지연도 상한(`mlMaxDelaySeconds`), 가중치(0.35), 신뢰도 임계값으로 한 번 더 걸러요. 아티팩트는 pickle이 아니라 순수 JSON(계수, 피처명, 메트릭)이라 사람이 열어볼 수 있고, 로드할 때 임의 코드가 실행될 일이 없습니다.

### 프론트엔드: 네트워크가 불안정해도 화면이 죽지 않게

서비스 워커나 외부 라이브러리 없이 직접 구현했습니다:

- **재시도.** 5xx나 네트워크 에러만 백오프로 재시도하고, 4xx는 절대 다시 안 때립니다.
- **Stale 캐시 폴백.** 공개 리스크 응답을 `sessionStorage`(512 kB 상한)에 캐시해 두고, 새 요청이 실패하면 캐시된 데이터 + "좀 전 데이터입니다" 배너 + 시각을 함께 보여줍니다.
- **연결 상태 인식.** `navigator.onLine`, `NetworkInformation.effectiveType`을 봐서 오프라인이나 저속 네트워크면 배너를 띄워요.
- **텔레메트리.** 재시도 횟수, 폴백 이벤트, 에러 상세를 세션 메모리에 담고 `/status`에 노출합니다. 브라우저 밖으로 아무것도 안 나갑니다.

### DynamoDB 싱글 테이블: 왜 Postgres가 아닌가

| 접근 패턴 | PK | SK |
| --- | --- | --- |
| 사용자 프로필 | `USER#{userId}` | `PROFILE` |
| 저장 장소 목록 | `USER#{userId}` | `SAVED_PLACE#{id}` (begins_with) |
| 저장 경로 목록 | `USER#{userId}` | `SAVED_ROUTE#{id}` (begins_with) |
| 일일 사용량 카운터 | `TENANT#{tenantId}` | `USAGE#{date}#{feature}` |

프리뷰 단계에서 Postgres를 띄우면 인스턴스가 돌아가는 내내 비용이 나옵니다. DynamoDB 온디맨드는 유휴 비용이 0이고, 소유자 범위 조회가 한 자릿수 ms, 조건부 쓰기로 낙관적 동시성까지 잡혀요. 대신 공간 쿼리가 안 되니까, 그건 PostGIS 확장 경로로 빼뒀습니다 (ADR-006).

### VRP 코스트 행렬: 위험을 초 단위로 바꾸는 공식

다중 경유지 최적화에서 각 엣지 (i, j)의 코스트를 이렇게 계산합니다:

```
adjusted_cost = base_duration * duration_weight
    + weather_risk * weather_weight * 6
    + traffic_risk * traffic_weight * 6
    + flood_risk * flood_weight * 8
    + alert_risk * alert_weight * 10
    + (distance_km * distance_weight)
```

승수가 6, 6, 8, 10으로 다른 이유가 있어요. 홍수(8)와 경보(10)는 "불쾌한 수준"이 아니라 "도로가 막히는 수준"과 상관되니까 더 무겁게 잡았습니다. 행렬에서 엣지가 빠진 경우(경로 없음)는 24시간 페널티를 넣어서 사실상 라우팅에서 제외해요. ML 섀도 모델이 켜져 있으면 이 기본 코스트 위에 신뢰도 게이트 지연이 추가됩니다.

---

## 기술 스택

| 레이어 | 기술 |
| --- | --- |
| 프론트엔드 | React 19, TypeScript, Vite, MapLibre GL, Lucide, Playwright E2E, axe-core |
| Platform API | Java 21, Spring Boot 3.5, Spring Security (OAuth2 Resource Server), AWS SDK v2 |
| 리스크 엔진 | Python 3.12, FastAPI, Pydantic, OR-Tools, scikit-learn |
| 데이터 | DynamoDB 싱글 테이블, S3 기상 아티팩트, PostGIS (확장 경로) |
| 인프라 | CloudFront, API Gateway, Lambda, Cognito, CloudWatch, Terraform |
| CI/CD | GitHub Actions OIDC (장기 키 없음), Maven, pytest, Playwright, 번들 예산 |

---

## 스크린샷

목업이 아니라 실행 중인 앱을 Playwright로 캡처한 겁니다. 다시 찍으려면: `npm run screenshots:release --prefix web`.

| 홈 | 대시보드 |
| --- | --- |
| ![홈](docs/screenshots/home-live.png) | ![대시보드](docs/screenshots/dashboard-live.png) |

| 경로 비교 | 운영 상태 |
| --- | --- |
| ![경로](docs/screenshots/map-route-live.png) | ![상태](docs/screenshots/status-live.png) |

| 모바일 |
| --- |
| ![모바일](docs/screenshots/home-mobile-live.png) |

---

## 로컬에서 돌려보기

### 준비물

풀스택은 Docker 하나면 됩니다. 개별 실행하려면 Node 20+, Python 3.12+, Java 21이 필요한데, Windows라면 `.tools/`에 Java와 Maven이 들어 있으니까 따로 설치 안 해도 돼요.

### 풀스택

```powershell
docker compose up --build
```

| 서비스 | 주소 |
| --- | --- |
| 웹 앱 | http://localhost:5173 |
| 리스크 엔진 API 문서 | http://localhost:8000/docs |
| Platform API 헬스 | http://localhost:8080/health |

### 프론트엔드만

```powershell
npm install --prefix web
npm run dev --prefix web        # 개발 서버
npm run build --prefix web      # 프로덕션 빌드
npm run test:e2e --prefix web   # Playwright E2E 28개
```

### Platform API (Spring Boot)

```powershell
cd services/platform-api
../../scripts/mvn.ps1 --batch-mode test   # 53개 테스트
```

### 리스크 엔진 (Python)

```powershell
$env:PYTHONPATH = 'services/api'
python -m pytest services/api/tests -q    # 78개 테스트
```

---

## 보안

인증은 Cognito JWT를 쓰고, Google OAuth 페더레이션 경로는 문서화해 뒀습니다. 공개 지도 API는 로그인 없이 쓰되 서버에서 레이트 리밋을 걸고, `/me/**`는 유효 토큰이 없으면 401입니다.

핵심 하드닝:

- DynamoDB 파티션 키를 소유자 범위로 잡고 조건부 쓰기/삭제를 써서, 다른 테넌트 데이터에 접근할 수 있는 경로 자체가 없습니다.
- 멱등성 키는 SHA-256 해시 후 테넌트 범위로 저장. 원본 키는 어디에도 남지 않아요.
- Spring API에 고정 윈도우 레이트 리밋 (인메모리 기본, Redis 전환 가능).
- CloudFront 오리진 검증(`X-Origin-Verify`), CSP, frame-deny, referrer policy.
- 아웃바운드 HTTP는 허용 목록 + HTTPS + 리다이렉트 차단 + 메타데이터/사설 네트워크 거부.
- 배포는 GitHub Actions OIDC. 장기 AWS 액세스 키가 이 프로젝트 어디에도 없습니다.
- 프론트엔드 E2E에 지도 마커 XSS, 인증 불가 시 메시징, stale 데이터 폴백 시나리오가 포함되어 있어요.

---

## ML 워크플로우

기본은 섀도 모드입니다. 룰 기반 점수가 권위이고, ML이 코스트를 바꾸려면:

1. 학습 + 백테스트 (MAE, RMSE, p95 vs. baseline)
2. 릴리스 게이트 통과 후 CLI로 프로모션 (`served_to_users=true`)
3. 런타임 환경변수 활성화
4. 개별 요청에서 `useMlServedCost=true`

하나라도 빠지면 fail-closed. 아티팩트는 pickle이 아니라 JSON이라 열어볼 수 있습니다.

```powershell
# 학습
python services/api/scripts/train_vrp_delay_model.py --input <dataset> --output <artifact> --model-version v1

# 프로모션 (릴리스 게이트 통과 필요)
python services/api/scripts/promote_vrp_delay_model.py --input <shadow> --output <served>

# 서빙 코스트 전체 흐름 데모
python services/api/scripts/run_vrp_served_cost_demo.py --artifact-dir tmp/demo-vrp-ml --model-version demo-v1
```

---

## 테스트

마지막 전체 실행: 2026-07-07.

| 대상 | 결과 |
| --- | --- |
| Spring Platform API (53개) | 통과 |
| Python 리스크 엔진 (78개) | 통과 |
| Playwright E2E (28개) | 통과 |
| 프론트엔드 lint + 빌드 + 번들 예산 | 통과 |
| 디자인 lint | 에러 0, 경고 0 |
| 의존성 감사 | high 취약점 0 |
| ML 서빙 코스트 데모 | 통과 |

**번들 예산** (CI에서 강제): 초기 JS 98.8 / 180 kB gz, MapLibre 벤더 278 / 320 kB gz, CSS 21.8 / 90 kB gz.

**로컬 스트레스 테스트** (외부 호출 없음, 인프로세스): 180 요청 / 동시성 8 / 실패 0건. 캐시 탄 엔드포인트 p95가 500 ms 아래. 다중 경유지 계획 p95 ~3.2 s가 현재 보틀넥인데, 동기 행렬 연산 때문이고 비동기 잡으로 빼는 게 다음 단계입니다.

```powershell
python perf/local_api_stress.py --requests 180 --concurrency 8
```

---

## 데이터 모델

DynamoDB 싱글 테이블:

| PK | SK | 용도 |
| --- | --- | --- |
| `USER#{userId}` | `PROFILE` | 프로필 |
| `USER#{userId}` | `SAVED_PLACE#{id}` | 저장 장소 |
| `USER#{userId}` | `SAVED_ROUTE#{id}` | 저장 경로 + 모니터링 설정 |
| `TENANT#{tenantId}` | `USAGE#{date}#{feature}` | 일일 사용량 |

PostGIS 확장 경로는 문서화만 해뒀고 기본 배포에는 포함 안 됩니다. GiST 인덱스, 소유자 RLS, 경로 노출 관측, 테넌트/워크스페이스 테이블까지 설계되어 있어요. [docs/data-model.md](docs/data-model.md), [docs/relational-data-model.md](docs/relational-data-model.md) 참고.

---

## 배포

AWS `us-east-1`, 서버리스 우선입니다. CloudFront + Private S3, API Gateway + Lambda, DynamoDB 온디맨드, Cognito. Kubernetes도 Aurora도 매니지드 Redis도 안 씁니다. 프리뷰 단계에서 그건 비용 낭비니까요.

인프라는 전부 Terraform으로 관리하고, CI/CD는 GitHub Actions OIDC(정적 자격 증명 없음). CloudFront에 US/KR 지역 제한을 걸었고, 리소스 태그는 `Project=awsresumeproject`, `ManagedBy=IaC`, `Environment=dev`로 통일했습니다.

자세한 건 [배포 문서](docs/deployment.md)와 [비용 모델](docs/cost-model.md)에 있습니다.

---

## 지금 상태

**되는 것:** 기상 경로 비교, 경보 검색, 워치리스트, SaaS 쿼터, 운영 상태 페이지, 구조화된 에러 컨트랙트, 전 레이어 테스트, 번들 예산, 로컬 스트레스 하네스.

**아직인 것:** 배포 환경 Google OAuth 시크릿 연결, 공개 트래픽용 WAF, 다중 경유지 비동기 잡, HRRR/MRMS 래스터 프로덕션 주기, 합성 모니터링.

현재 `v0.1.0-preview` (2026-07). 로드맵은 [CHANGELOG.md](CHANGELOG.md)에.

---

## 문서 목록

- [아키텍처 개요](docs/architecture.md)
- [데모 플레이북](docs/demo-playbook.md) (2분 면접 스크립트 포함)
- [리서치·보안 근거](docs/architecture/research-and-security-basis.md)
- [애플리케이션 리질리언스](docs/architecture/application-resilience.md)
- [백엔드 프로덕션 하드닝](docs/architecture/backend-production-hardening.md)
- [기상 리스크 파이프라인](docs/architecture/weather-risk.md)
- [VRP 경로 엔진](docs/architecture/route-engine-vrp-foundation.md)
- [ML 섀도 모델](docs/architecture/vrp-ml-shadow-model.md)
- [WZDx 도로 이벤트 피드](docs/architecture/road-events-wzdx-feeds.md)
- [SaaS 하드닝 체크리스트](docs/saas-production-hardening-checklist.md)
- [배포 가이드](docs/deployment.md)
- [Google OAuth 설정](docs/google-auth.md)
- [런북](docs/runbooks/)
- [ADR](docs/adr/)
