    return ResponseEntity.status(429)
        .header("Retry-After", "60")
        .body("{\"error\": \"rate_limit_exceeded\"}");
```

---

#### Q13. CQRS 패턴이란?
**답변:**
> "Command(Query)와 Query(Read)의 책임과 데이터 모델을 분리하는 패턴입니다. 
> 
> 우리 프로젝트에서는 화물 등록 (Write) 은 `freight_load` 테이블에 쓰고, 화물 목록 조회 (Read) 는 30 초마다 refresh 되는 `mv_open_loads_summary` materialized view 에서 읽습니다. 쓰기 부하가 읽기 성능에 영향을 주지 않도록 분리했습니다."

---

#### Q14. WebSocket 은 어디에 쓰나요?
**답변:**
> "실시간 트럭 추적 대시보드에서 사용합니다. nginx 가 least_conn 방식으로 WebSocket 연결을 분산하고, tracking 서비스가 TimescaleDB 에서 최신 위치를 읽어 push 합니다. 10k 동시 연결을 처리할 수 있습니다."

---

#### Q15. Terraform 으로 무엇을 관리하나요?
**답변:**
> "AWS 인프라 전체를 코드로 관리합니다: VPC, 서브넷, 보안그룹, RDS, ElastiCache, MSK(Kafka), ECS Fargate 서비스, CloudFront 배포 등. 
> 
> `terraform plan`으로 변경 사항을 리뷰하고, GitHub Actions OIDC 로 AWS 에 assume-role 해 장기 키를 사용하지 않습니다."

---

### 🟡 심화 (15 개)

#### Q16. "트래픽이 10 배가 되면 어떻게 할 건가?"
**답변:** (위 7 장 참조)

---

#### Q17. "가장 어려웠던 기술적 문제는 무엇이었나?"
**답변:** (위 6 장 스토리 1~3 중 하나 선택)
> "동시 입찰 데드락 문제가 가장 어려웠습니다. 500 명이 동시에 입찰할 때 p99 이 2.1 초로 급증했고, PostgreSQL slow query log 와 pg_stat_activity 를 분석한 결과 SELECT FOR UPDATE 가 암묵적으로 걸려 47 개 프로세스가 락 대기를 하고 있었습니다. 
> 
> 해결책은 Kafka write flattening 을 도입해 HTTP 는 즉시 202 를 반환하고, 실제 DB insert 는 consumer 가 순차 처리하도록 바꾸는 것이었습니다. 그 결과 p99 이 45ms 로 떨어졌습니다."

---

#### Q18. "실수했던 경험과 배운 점은?"
**답변:**
> "Saga 정산 서비스에서 Step 4(지급) 까지 완료 후 Step 5(송장) 가 실패하면 돈은 이체됐는데 송장이 없는 상태가 된 적이 있습니다. Compensation 로직이 없었기 때문입니다.
> 
> 이후 모든 Saga step 에 멱등성을 추가하고, 실패 시 역방향 트랜잭션을 수행하는 compensation 로직을 넣었습니다. 또한 DLQ 를 만들어 5 회 재시도 후 실패한 이벤트는 운영팀이 수동 처리할 수 있도록 했습니다."

---

#### Q19. "성능 최적화를 위해 어떤 노력을 했나?"
**답변:** (위 6 장 스토리 2 참조)
> "28M GPS 이벤트를 조회하는 쿼리가 1,400ms 걸렸는데, EXPLAIN ANALYZE 로 분석한 결과 인덱스가 없어 전체 테이블 스캔을 하고 있었습니다. 
> 
> Covering Index 를 만들어 heap fetch 를 제거하고, Keyset pagination 으로 OFFSET 을 없앴습니다. 그 결과 6ms 로 떨어졌습니다."

---

#### Q20. "보안 취약점을 어떻게 방어했나?"
**답변:** (위 8 장 참조)

---

#### Q21. "테스트 커버리지는 얼마나 되나?"
**답변:**
> "단위 테스트 371 개 (Java 261, Python 110), E2E 테스트 28 개 (Playwright) 를 돌립니다. 커버리지 수치보다는 'critical user journey'를 모두 커버하는 데 집중했습니다.
> 
> 특히 Kafka consumer, Saga orchestrator 같은 핵심 로직은 Testcontainers 로 실제 Kafka/PostgreSQL 과 통합 테스트를 합니다."

---

#### Q22. "CI/CD 파이프라인이 실패했을 때 디버깅 방법은?"
**답변:**
> "GitHub Actions 로그에서 실패 단계를 확인하고, Terraform plan 오류면 리소스 의존성 문제를, 테스트 실패면 최근 커밋의 코드 변경을 먼저 봅니다.
> 
> Playwright E2E 실패 시에는 스크린샷과 비디오 녹화를 통해 UI 상태를 확인합니다."

---

#### Q23. "마이크로서비스 간 통신에서 장애가 나면 어떻게 처리하나?"
**답변:**
> "동기 호출 (REST) 은 circuit breaker (Resilience4j) 로 3 회 실패 시 fallback 을 반환하고, 비동기 호출 (Kafka) 은 DLQ 로 이관해 수동 재처리합니다.
> 
> 또한 X-Ray 로 분산 트레이싱을 해 어느 서비스에서 지연이 발생하는지 모니터링합니다."

---

#### Q24. "데이터 일관성은 어떻게 보장하나?"
**답변:**
> "Saga 패턴으로 eventual consistency 를 추구합니다. 강한 일관성이 필요한 경우 (예: 결제) 는 atomic wallet operations 으로 debit/credit 을 단일 트랜잭션으로 처리합니다.
> 
> 매일 자정에 automated reconciliation 작업으로 wallet_balance 와 settlement_total 을 대조합니다."

---

#### Q25. "모니터링은 어떻게 하나?"
**답변:**
> "CloudWatch Dashboard 에서 CPU, 메모리, 네트워크, Kafka backlog, DB connections 를 실시간으로 보고, 임계치 초과 시 PagerDuty 로 알림이 갑니다.
> 
> Micrometer 로 애플리케이션 메트릭 (request rate, error rate, latency percentiles) 을 수집하고, Grafana 에서 시각화합니다."

---

#### Q26. "로그는 어떻게 관리하나?"
**답변:**
> "모든 서비스에 structured logging (JSON) 을 적용하고, correlation ID (`X-Request-Id`) 를 nginx → 서비스 → Kafka → consumer 까지 전파합니다.
> 
> CloudWatch Logs Insights 로 특정 request 의 전체 흐름을 추적할 수 있습니다."

---

#### Q27. "확장성을 고려한 설계 원칙은?"
**답변:**
> "1. **Stateless**: 모든 서비스를 stateless 로 만들어 오토스케일링 가능
> 2. **Event-driven**: Kafka 로 service coupling 최소화
> 3. **Database per service**: 각 서비스가 자신의 DB 소유, 직접 접근 금지
> 4. **API Gateway**: 인증/인가/rate limiting 을 gateway 에서 통일"

---

#### Q28. "비용 최적화는 어떻게 했나?"
**답변:**
> "1. **DynamoDB on-demand**: 유휴 비용 제로 (프리뷰용)
> 2. **S3 + CloudFront**: 정적 파일 호스팅 비용 최소화
> 3. **Lambda**: 요청 있을 때만 실행 (Risk Engine)
> 4. **Aurora Serverless**: 0-1 ACU auto-pause (프로덕션)
> 5. **NAT Gateway 제거**: VPC endpoint 로 아웃바운드 트래픽 우회"

---

#### Q29. "LLM 을 어디에 활용했나?"
**답변:**
> "NL2Opt (Natural Language to Optimization) 기능에서 사용자의 자연어 입력 ("서울에서 부산까지 가는데 비 오는 길 피해서, 오후 5 시까지 도착하게") 을 VRP 제약조건으로 변환할 때 LiteLLM 과 Langfuse 를 사용했습니다.
> 
> prompt engineering 으로 extraction 정확도를 92% 까지 올렸습니다."

---

#### Q30. "오케스트레이션 vs 코레오그래피 차이는?"
**답변:**
> "**오케스트레이션**은 중앙 오케스트레이터 (SettlementSagaOrchestrator) 가 모든 step 을 통제합니다. 구현이 간단하지만 오케스트레이터가 bottleneck 이 될 수 있습니다.
> 
> **코레오그래피**는 각 서비스가 이벤트만 구독해 자율적으로 행동합니다. 결합이 느슨하지만 흐름 추적이 어렵습니다.
> 
> 우리는 정산처럼 순서가 중요한 경우는 오케스트레이션, 입찰처럼 독립적인 경우는 코레오그래피를 썼습니다."

---

### 🔴 함정 (10 개)

#### Q31. "왜 이 기술을 선택했나?" (예: 왜 Java?)
**❌ 나쁜 답변:** "팀이 Java 를 잘 알아서요"
**✅ 좋은 답변:** "Platform API 는 인증, 테넌트 관리, 할당량 같은 비즈니스 로직이 많아 타입 안정성과 엔터프라이즈급 생태계가 중요한데, Java+Spring 이 이를 가장 잘 만족했습니다. 반면 Risk Engine 은 OR-Tools 나 ML 라이브러리를 써야 해서 Python 이 필수였고, 두 언어의 강점을 살려 혼용했습니다."

---

#### Q32. "이 기능 왜 없나?" (예: 왜 실시간 경로 재탐색이 없나?)
**❌ 나쁜 답변:** "시간 없어서 안 만들었어요"
**✅ 좋은 답변:** "우선순위 문제였습니다. 초기 MVP 는 '경로 비교'에 집중했고, 실시간 재탐색은 Phase 2 roadmap 에 있습니다. 현재는 사용자가 출발 전 3 가지 경로를 비교해 선택하는 플로우로, 주행 중 재탐색은 지도 앱 (네이버/카카오) 에 맡기는 게 낫다고 판단했습니다."

---

#### Q33. "트레이드오프를 설명해보라" (예: MSA vs 모놀리스)
**✅ 좋은 답변:**
> "MSA 를 선택함으로써 얻은 것: 각 서비스 독립 확장, 기술 스택 다양성, 장애 격리
> 잃은 것: 운영 복잡도, 분산 트레이싱 필요, 데이터 일관성 관리 어려움
> 
> 우리 경우에는 4 가지 다른 스케일링 축 (쓰기/연결/경쟁/일관성) 이 있어 MSA 가 맞았지만, 트래픽이 1/10 이었다면 모놀리스로 시작해 점진적으로 분리했을 것입니다."

---

#### Q34. "만약 다시 만든다면 무엇을 다르게 할 건가?"
**✅ 좋은 답변:**
> "1. **초기에 더 많은 메트릭 수집**: 부하 테스트 전에 baseline metric 이 없어 최적화 효과를 정량화하기 어려웠습니다.
> 2. **Feature Flag 더 일찍 도입**: 릴리스와 배포를 분리하지 않아 롤백이 어려웠습니다.
> 3. **Documentation-first**: ADR 을 더 일찍 작성해 결정 사유를 기록했어야 합니다."

---

#### Q35. "이 프로젝트의 약점은?"
**✅ 좋은 답변:**
> "1. **운영 복잡도**: 8 개 서비스 + Kafka + Redis + PostgreSQL 을 모두 모니터링해야 합니다.
> 2. **데이터 일관성**: eventual consistency 로 인해 정산 지연 발생 시 사용자 혼란 가능성
> 3. **학습 곡선**: 새 개발자가 MSA, Kafka, Saga, CQRS 등을 모두 익히는 데 시간 소요
> 
> 하지만 이는 트레이드오프의 결과이며, 우리는 문서화와 자동화로 이를 완화하고 있습니다."

---

#### Q36. "경쟁사 (예: Google Maps) 와 차별점은?"
**✅ 좋은 답변:**
> "Google Maps 는 '최단 시간'에 최적화되어 있지만, FreightScaler 는 '최소 위험'에 최적화되어 있습니다. 
> 
> 또한 화물 운송 특화 기능 (입찰, 정산, 차량 추적) 을 제공하며, B2B 물류 워크플로우를端到端으로 디지털화한다는 점에서 다릅니다."

---

#### Q37. "데이터 정확도는 어떻게 보장하나?" (예: 기상 데이터)
**✅ 좋은 답변:**
> "NWS/NOAA 공식 API 를 사용하고, 데이터 unavailable 시 UI 에 '데이터 없음'으로 명시합니다. fake data 를 보여주지 않는 게 원칙입니다.
> 
> 또한 HRRR/MRMS 래스터를 사전 생성해 API 응답 시간을 단축하고, 60 초 TTL cache 로 중복 호출을 방지합니다."

---

#### Q38. "사용자 피드백을 어떻게 반영했나?"
**✅ 좋은 답변:**
> "초기 사용자 테스트에서 '경로가 너무 많다'는 피드백을 받아 3 가지 (최단/최안전/균형) 로 줄였습니다. 
> 
> 또한 '정산이 언제 되나?' 문의가 많아 status 페이지와 이메일 알림을 추가했습니다."

---

#### Q39. "기술 부채는 어떻게 관리하나?"
**✅ 좋은 답변:**
> "1. **Tech Backlog**: 리팩토링 항목을 별도 백로그로 관리
> 2. **20% 규칙**: 스프린트 용량의 20% 를 부채 상환에 할당
> 3. **Automated refactoring**: SonarQube 로 코드 스멜 자동 감지
> 
> 예: 초기 JPA 낙관적 락을 JDBC 로 교체한 것도 부채 상환의 일환이었습니다."

---

#### Q40. "이 프로젝트에서 무엇을 배웠나?"
**✅ 좋은 답변:**
> "1. **성능 최적화는 측정에서 시작한다**: EXPLAIN ANALYZE, slow query log, micrometer metric 없이 감으로 최적화하지 않기
> 2. **분산 시스템은 실패를 가정한다**: Compensation, DLQ, retry, circuit breaker 필수
> 3. **문서는 코드다**: ADR, runbook, architecture diagram 이 없으면 유지보수 불가
> 4. **트레이드오프 명확히**: 모든 결정은 장단점이 있으며, 컨텍스트에 따라 달라진다"

---

## 11. 회고 & 개선점

### 잘한 점 ✅
1. **측정 기반 최적화**: EXPLAIN ANALYZE, slow query log 로 정확한 병목 지점 찾음
2. **장애 가정 설계**: Compensation, DLQ, idempotency 로 분산 시스템 복원력 확보
3. **문서화 문화**: ADR 24 개 작성, architecture diagram 으로 의사결정 사유 기록
4. **자동화**: CI/CD, Terraform IaC, 부하 테스트 자동화로 인간 실수 최소화

### 아쉬운 점 ⚠️
1. **초기 메트릭 부족**: 부하 테스트 전 baseline 이 없어 최적화 효과 정량화 어려움
2. **Feature Flag 지연**: 릴리스와 배포 분리가 늦어져 롤백 어려움
3. **모니터링 사각지대**: Kafka consumer lag, WebSocket 연결 수 등 일부 metric 누락

### 향후 계획 📅
1. **Phase 5: Predictive Maintenance**: ML 로 차량 고장 예측
2. **국제화**: 캐나다, 멕시코 확장을 위한 multi-region 아키텍처
3. **Blockchain Settlement**: 스마트 컨트랙트로 정산 자동화 (검토 중)

---

## 📚 참고 문서 링크

- [Architecture Decision Records](docs/adr/)
- [SQL 튜닝 사례 연구](docs/database/tuning-case-studies.md)
- [MSA 진화 과정](docs/adr/0024-freight-platform-msa-evolution.md)
- [데이터 모델](docs/relational-data-model.md)
- [보안 가이드](docs/security.md)

---

## 🎯 면접 당일 체크리스트

### 기술 면접 전
- [ ] 프로젝트 README 다시 읽기
- [ ] 아키텍처 다이어그램 그리기 연습
- [ ] Q1~Q40 답변 3 번씩 말해보기
- [ ] "어려웠던 문제" 스토리 3 개 암기

### 코딩 테스트 전
- [ ] SQL 인덱스 설계 문제 복습
- [ ] Kafka consumer 구현 연습
- [ ] Saga 패턴 의사코드 작성

### 문화적 적합성 면접 전
- [ ] 팀워크 경험 스토리 준비
- [ ] 갈등 해결 경험 정리
- [ ] 학습 방법론 설명 준비

---

**행운을 빕니다! 🍀**
