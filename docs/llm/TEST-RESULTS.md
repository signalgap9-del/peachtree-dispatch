# LLM Test Results

## E2E API Test (scripts/test_llm_e2e.py)
Date: 2026-07-26
Model: qwen3.8-max-preview (Alibaba Cloud MaaS)
Result: 5/5 passed

| Test | Result | Latency | Details |
|------|--------|---------|---------|
| Chat Completion | PASS | 4.3s | 88 tokens |
| Route Advice | PASS | 29s | I-5→I-84→I-15→I-40→I-75 |
| NL2Opt Extraction | PASS | 16s | hazmat=true, departure=08:00, avoid=highway |
| Local Embedding | PASS | 0.8s | 384-dim, similarity ranking correct |
| Intent Classification | PASS | 4/5 | fleet_optimize misclassified as route_plan |

### Test details

**Chat Completion**: Basic safety analyst prompt. Confirms API connectivity,
token counting, and response quality.

**Route Advice**: Seattle-to-Miami hazmat truck routing with storm context.
Thinking mode disabled (`enable_thinking=false`) to keep latency under 30s.
Model recommended southern corridor (I-40 through I-75) to avoid storm impact.

**NL2Opt Extraction**: Korean input ("시애틀에서 8시 출발, 트럭 위험물, 고속도로 회피, 리스크 최소화")
extracted to structured JSON with correct hazmat flag, departure time,
avoid-highway soft constraint, and min_risk objective.

**Local Embedding**: `sentence-transformers/all-MiniLM-L6-v2` produces 384-dim
vectors. Flood-related queries rank higher in cosine similarity than unrelated
weather queries, confirming semantic discrimination.

**Intent Classification**: 5 Korean test cases. 4/5 correct. The fleet
optimization case ("배송 5대 최적 경로") was classified as `route_plan`
instead of `fleet_optimize`. Needs more fleet-specific few-shot examples.

---

## VRP Pipeline Test (scripts/test_vrp_pipeline.py)
Seattle → Miami, TRUCK:
- Fastest: 3,657.9 mi, 69.4 hrs, risk 58/100
- Lower weather risk: 4,039.0 mi, 77.9 hrs, risk 15/100
- Climate delay: 628 min
- NL2Opt formulation: objective=min_risk applied, 2 soft constraints pending mapping

### Pipeline steps verified

1. **Constraint input**: LLM-extracted JSON with stops, vehicle (truck, hazmat),
   departure (08:00), soft constraints (arrive_before storm, avoid highway),
   objective (min_risk).
2. **Route engine**: `build_directions()` returns primary route plus alternatives
   with distance, duration, risk score, and climate delay per route.
3. **NL2Opt formulation**: `formulate_vrp()` accepts constraints, builds solver
   parameters with objective and node list. Soft constraints `arrive_before`
   and `avoid` are extracted but not yet translated to solver penalties.
4. **Multi-stop VRP**: Requires explicit `stop_id` on each `RouteStop`. The
   NL2Opt layer should generate these before calling the solver.

---

## Known Gaps
- arrive_before/avoid constraint types need mapping in constraint_translators.py
- Multi-stop RouteStop requires stop_id field
- Embedding uses local sentence-transformers (API not available on token plan)
- qwen3.8 thinking mode causes 30-60s latency; use enable_thinking=false
- Intent classification: fleet_optimize needs more few-shot examples (4/5 accuracy)

---

## Reproducing

```powershell
# E2E API test (requires Alibaba MaaS API key in ~/.opencodex/config.json)
python scripts/test_llm_e2e.py

# VRP pipeline test (local, no API key needed)
python scripts/test_vrp_pipeline.py
```
