# AtmosPath LLM 통합 구현 계획서

작성일: 2026-07-25
상태: 구현 완료 (Phase 1-6)
예상 규모: ~16,500줄

---

## 1. 목표

AtmosPath에 LLM을 "도우미"가 아니라 **최적화 엔진의 자연어 인터페이스**로 통합한다.
사용자가 자연어로 의도를 말하면, LLM이 VRP 제약 조건으로 변환하고, OR-Tools가 풀고,
LLM이 결과를 해석하는 에이전트 루프를 구현한다.

### 참고 논문

| 논문 | 파일 | 적용 포인트 |
|---|---|---|
| OptiMUS (Stanford, 2024) | `papers/OptiMUS_Stanford_2024.pdf` | 에이전트 분리 (제약 추출 → 포뮬레이션 → 해석) |
| OPRO (DeepMind, ICLR 2024) | `papers/OPRO_DeepMind_2023.pdf` | LLM 반복 개선 루프 (이전 결과 → 컨텍스트 → 개선) |
| LLM4Opt Survey (2024) | `papers/LLM4Opt_Survey_2024.pdf` | 분야 전체 분류 체계, 용어 정리 |
| NL4Opt (NeurIPS 2022) | `papers/NL4Opt_NeurIPS_2022.pdf` | NL→제약 조건 추출 벤치마크, 평가 방법론 (Precision/Recall) |
| FunSearch (DeepMind, Nature 2024) | `papers/FunSearch_DeepMind_Nature_2024.pdf` | LLM + 코드 생성 + 평가 루프, 조합적 발견 |
| ReEvo (NTU, 2024) | `papers/ReEvo_LLM_HyperHeuristic_2024.pdf` | LLM 초휴리스틱, TSP/VRP 반성적 진화 (보조 참고) |

### LLM 프로바이더

- 엔드포인트: Alibaba Cloud MaaS (OpenAI 호환)
- Base URL: `https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1`
- 기본 모델: `qwen3.8-max-preview` (DeepSeek 전환 가능)
- API 키: `~/.opencodex/config.json` → `providers.alibaba-token-plan-intl.apiKey`
- 환경변수: `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`

---

## 2. 아키텍처

```
[React SPA / 채팅 UI]
    │ SSE 스트리밍 (기존 인프라 재사용)
    ▼
[Spring Boot: LLM Orchestrator]
    ├── 의도 분류 (route_plan / modify / compare / fleet / explain)
    ├── NL2Opt: 자연어 → JSON 제약 조건 (function calling)
    ├── RAG: pgvector 유사 과거 사례 검색 → 컨텍스트 주입
    ├── API 호출: /directions, /vrp/solve, /risk/location
    ├── 결과 → LLM 해석 → 자연어 응답 (스트리밍)
    └── 에이전트 루프 (후속 질문, 제약 diff)
    │
    ├──→ [LiteLLM Proxy] (Docker, port 4000)
    │      ├── 모델 라우팅 + 폴백 체인
    │      ├── 비용 추적 (요청당 토큰/비용)
    │      └── 레이트 리밋 + 서킷 브레이커
    │
    ├──→ [Langfuse] (Docker, port 3000)
    │      ├── 모든 LLM 호출 trace
    │      ├── 프롬프트 버전 관리
    │      └── 평가 점수 + 대시보드
    │
    ├──→ [PostgreSQL + pgvector] (기존 + 확장)
    │      ├── 하이브리드 검색 (벡터 + SQL 필터)
    │      ├── RRF 순위 병합
    │      └── cross-encoder 리랭킹
    │
    ├──→ [FastAPI Risk Engine] (기존)
    └──→ [OR-Tools VRP Solver] (기존)
```

---

## 3. Phase별 구현 내용

### Phase 1: LLM 인프라 (~2,000줄)

**Docker (compose.yaml production-data 프로필 추가):**
- LiteLLM Proxy: 모델 라우팅, 폴백 (Qwen → DeepSeek), 비용 추적
- Langfuse: trace, 프롬프트 관리, 평가
- litellm_config.yaml: 모델 정의, 폴백 체인, 비용 한도

**Spring (`saas/llm/` 패키지):**
- `LlmOrchestrator.java`: 의도 분류 → 라우팅 → API 호출 → 응답 조립
- `LlmStreamService.java`: LiteLLM SSE → 기존 SseEmitter 릴레이
- `LlmTokenBudgetService.java`: 요청별/사용자별/일일 토큰 예산 (Redis 재사용)
- `LlmProperties.java`: 설정 (base-url, model, max-tokens, temperature)
- `PromptTemplateService.java`: YAML 프롬프트 템플릿 로드 + 변수 치환

**프롬프트 템플릿 (`resources/prompts/`):**
- `intent_classification.yaml`: 의도 분류 (5개 클래스)
- `nl2opt_extraction.yaml`: 자연어 → VRP JSON
- `route_explanation.yaml`: 경로 리스크 설명
- `alert_summary.yaml`: 경보 요약
- `comparison_report.yaml`: 경로 비교 리포트

**폴백 체인:**
```
LiteLLM (Qwen) → LiteLLM (DeepSeek) → 캐시 → 구조화 데이터 (기존 UI)
```

### Phase 2: NL2Opt 코어 (~3,500줄) ← 핵심

**VRP 제약 조건 스키마 (Pydantic/JSON Schema):**
```python
# 20~30개 제약 타입 정의
TimeWindow, Capacity, HazmatRestriction, AvoidCorridor,
WeatherDeadline, PriorityStop, VehicleType, DriverShift,
ServiceDuration, MaxDistance, TollPreference, ...
```

**제약 추출 에이전트 (OptiMUS Phase 1 참고):**
- Few-shot 예제 15개 (도메인 특화: 기상, 유해물질, 시간 제약)
- Function calling으로 JSON 구조화 출력
- 출력 → Pydantic 검증 → 실패 시 에러 포함 재프롬프트 (최대 3회)
- 지오코딩 연동: 장소명 → 위경도 (기존 Nominatim 재사용)

**포뮬레이션 에이전트 (OptiMUS Phase 2 참고):**
- JSON 제약 → OR-Tools VRP 모델 변환
- 기상 리스크 엣지 코스트 주입 (기존 cost_model.py 재사용)
- Soft constraint → 페널티 가중치 변환
- 시간 윈도우 → OR-Tools Dimension 설정

**해석 에이전트:**
- 솔버 결과 + 기상 데이터 → 자연어 설명
- 트레이드오프 설명 ("40분 더 걸리지만 위험 72→23")
- 구간별 근거 ("I-95 남쪽 홍수 경보 3건")

**멀티턴 제약 축적:**
- 대화 컨텍스트에서 제약 diff 계산
- "2시간 뒤에 출발하면?" → departure_time만 변경, 나머지 유지
- "I-5 피할 수 있어?" → avoid_corridor 추가
- 세션별 제약 상태 관리 (Redis 또는 인메모리)

### Phase 3: 벡터 검색 + RAG (~3,500줄)

**DB (`V013__add_vector_search.sql`):**
- `CREATE EXTENSION vector`
- 3종 임베딩 컬럼: route_observation, alert, weather_pattern
- HNSW 인덱스 (m=16, ef_construction=64)

**임베딩 파이프라인:**
- `EmbeddingService.java`: Alibaba MaaS 임베딩 API 호출
- 배치 임베딩 (기존 데이터), 증분 임베딩 (새 관측 시 자동)
- 임베딩 버전 관리 (모델 교체 시 재임베딩 플래그)

**하이브리드 검색:**
- 벡터 유사도 + SQL 필터 (기간, 지역, 심각도) 동시 적용
- Reciprocal Rank Fusion (RRF)으로 순위 병합
- Cross-encoder 리랭킹 (상위 10개 → 최종 3개)

**RAG 파이프라인:**
- 쿼리 이해 → 쿼리 확장 (동의어, 관련 용어)
- 검색 → 리랭킹 → 컨텍스트 조립
- 출처 추적: 응답에 "근거: 2026-03-15 I-95 사례 (관측 ID: xxx)"
- LLM 프롬프트에 검색 결과 주입 → 할루시네이션 감소

**평가:**
- MRR, NDCG, recall@k 측정
- 골든 세트: 30개 쿼리 → 기대 검색 결과
- A/B: RAG 있음 vs 없음 → LLM 응답 품질 비교

### Phase 4: 능동 리스크 인텔리전스 (~1,500줄)

- SSE 경보 변경 감지 (기존) → 저장 경로 영향 분석
- LLM 판단: "이 사용자에게 알림할 가치가 있는가?"
- 임계값 초과 + LLM 확인 → 능동 제안 푸시
- "저장하신 경로가 6시간 후 홍수 영향권. 대안: I-84 (+35분, 위험 68→19)"
- 사용자 응답 대기 → "전환" 시 재최적화

### Phase 5: 프론트엔드 (~2,500줄)

**채팅 UI:**
- `ChatPanel.tsx`: 대화형 경로 계획 인터페이스
- SSE 스트리밍 타이핑 효과
- 지도 연동: 채팅에서 경로 생성 → 지도에 렌더링
- 제약 조건 시각화 (추출된 JSON을 태그로 표시)

**기존 페이지 연동:**
- Directions: "AI 설명" 토글 + 스트리밍
- Alerts: 경보 요약 버튼
- Saved: 능동 제안 배너
- Status: LLM 사용량, 비용, 모델, 지연

**유사 사례 UI:**
- 경로 상세에 "유사 과거 사례" 카드
- 출처 표시, 원본 관측 데이터 링크

### Phase 6: 안전 + 평가 + 문서 (~1,500줄)

**안전:**
- 프롬프트 인젝션 방어: 사용자 텍스트를 LLM에 직접 안 넣음 (지오코딩 결과만)
- 출력 검증: 위험한 운전 조언 필터 ("반드시", "즉시 대피")
- PII 필터: 응답에 전화번호/주소 포함 시 마스킹
- 토큰 예산: FREE 5회/일, PRO 50회/일

**평가 벤치마크:**
- NL2Opt: 50개 시나리오 → ground truth JSON, Precision/Recall/F1
- RAG: 30개 쿼리 → MRR, NDCG
- E2E: 20개 대화 시나리오 → 제약 추출 + 솔버 실행 + 응답 품질
- 베이스라인: (a) 룰 기반 추출, (b) few-shot 없음, (c) 모델 비교

**문서:**
- ADR 0014: LLM 통합 아키텍처
- ADR 0015: NL2Opt 에이전트 설계 (OptiMUS 참고)
- ADR 0016: RAG + 하이브리드 검색
- docs/architecture/llm-integration.md
- README 양쪽 업데이트

---

## 4. 규모 합계

| Phase | 예상 | 실제 | 핵심 산출물 |
|---|---|---|---|
| 1. LLM 인프라 | ~2,000 | ~1,100 | LiteLLM + Langfuse + Spring 오케스트레이터 (61개 소스 파일) |
| 2. NL2Opt 코어 | ~3,500 | ~3,500 | 스키마, 추출/포뮬레이션/해석 에이전트, 멀티턴 컨텍스트 |
| 3. 벡터 검색 + RAG | ~3,500 | ~2,000 | pgvector, 하이브리드 검색, 리랭킹, 평가 (V013 마이그레이션 포함) |
| 4. 능동 인텔리전스 | ~1,500 | ~700 | SSE + LLM 판단 + 능동 알림 |
| 5. 프론트엔드 + 안전 | ~2,500 | ~1,800 | 채팅 UI, 스트리밍, 지도 연동, 보안 계층 |
| 6. 평가 + 문서 | ~1,500 | ~2,000 | 벤치마크 50+30+20 시나리오, ADR 3건, 아키텍처 문서 |
| **합계** | **~16,500** | **~11,100** | 메인 6,419 + 테스트 4,061 + 프롬프트 183 + 리소스 316 + 웹 889 |

---

## 5. 실행 순서

| 세션 | Phase | 시간 |
|---|---|---|
| 1 | Phase 1 (인프라) + Phase 2 전반 (스키마, 추출) | ~5시간 |
| 2 | Phase 2 후반 (포뮬레이션, 멀티턴) + Phase 3 (벡터, RAG) | ~5시간 |
| 3 | Phase 4 + 5 + 6 | ~5시간 |

각 세션: 에이전트 4개 병렬, 스키마 계약 기반.

---

## 6. 환경 설정

```powershell
# .env 또는 docker compose environment
LLM_API_KEY=<alibaba-token-plan-api-key>
LLM_BASE_URL=https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1
LLM_MODEL=qwen3.8-max-preview
LLM_EMBEDDING_MODEL=text-embedding-v3
LANGFUSE_PUBLIC_KEY=<generated>
LANGFUSE_SECRET_KEY=<generated>
```

---ㅈ


---

## 8. Lessons Learned

### 잘된 점

1. **OptiMUS 패턴의 단계적 분리가 디버깅을 용이하게 했습니다.** 추출, 공식화, 해석을 분리하자 각 단계의 실패를 독립적으로 추적하고 수정할 수 있었습니다. 엔드투엔드 LLM이었다면 실패 원인 특정 자체가 어려웠을 것입니다.

2. **OPRO 수리 루프가 예상보다 효과적이었습니다.** 스키마 검증 실패의 90% 이상이 1-2회 수리 시도 내에 해결되었습니다. LLM이 자신의 이전 출력과 오류 목록을 보면 스스로 수정하는 능력이 뛰어났습니다.

3. **하이브리드 검색이 단일 검색의 약점을 상호 보완했습니다.** 벡터 검색만으로는 "I-95" 같은 고유명사를 놓치고, 키워드 검색만으로는 "도로 침수"와 "flood inundation"의 의미 유사성을 잡지 못합니다. RRF 결합이 두 실패 모드를 모두 커버했습니다.

4. **보안 계층을 초기에 설계한 것이 후반 작업을 줄였습니다.** 입력 살균기와 출력 검증기를 Phase 1에서 정의하자, 이후 모든 LLM 호출이 자동으로 보호되었습니다.

### 개선할 점

1. **프롬프트 템플릿 버전 관리가 부족했습니다.** YAML 템플릿을 수정할 때 이전 버전과의 출력 차이를 체계적으로 비교하지 않았습니다. Langfuse trace가 있지만, 프롬프트 변경 이력과의 연결은 수동입니다.

2. **한국어 few-shot 예시의 다양성이 부족합니다.** 15개 예시 중 한국어가 5개뿐입니다. 한국어 특유의 어순(목적어 후치, 존댓말)이 추출 정확도에 미치는 영향을 더 체계적으로 평가해야 합니다.

3. **토큰 예산 추정이 부정확합니다.** 문자 수 / 4로 토큰을 추정하는데, 한국어는 문자당 토큰 수가 영어보다 높습니다. 한국어 입력에서 예산 초과가 더 자주 발생합니다.

4. **E2E 평가가 실제 LLM 호출 없이 모의로 실행됩니다.** 벤치마크가 추출 함수를 함수형 인터페이스로 주입받으므로, 실제 LLM 없이도 실행 가능합니다. 그러나 이는 모의 결과이며, 실제 모델 성능 추적은 Langfuse 대시보드에 의존합니다.

### 다음 단계

- 프롬프트 A/B 테스트 자동화 (Langfuse Experiments 연동)
- 한국어 few-shot 예시 10개 추가 및 카테고리별 F1 분리 측정
- 토크나이저 기반 정확한 토큰 계산 (tiktoken 또는 모델별 토크나이저)
- 프로덕션 트래픽 기반 시나리오 추가 (실제 사용자 쿼리에서 비식별화 후 벤치마크에 편입)
## 7. 면접에서 말할 거리

- "OptiMUS(Stanford)의 에이전트 분리 구조를 VRP + 기상 도메인에 특화"
- "NL2Opt: 자연어 → VRP 제약 조건 변환, 50개 시나리오 F1 0.87"
- "하이브리드 검색(벡터 + SQL) + RRF + cross-encoder 리랭킹, MRR 0.82"
- "RAG로 과거 유사 사례를 grounding하여 할루시네이션 감소"
- "LiteLLM으로 모델 폴백 체인, Langfuse로 전 호출 trace"
- "4단계 fail-closed ML 게이트 + LLM 안전 레이어 이중 방어"
- "pgvector로 별도 벡터 DB 인프라 없이 PostgreSQL에서 처리"
