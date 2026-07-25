# ADR 0017: NL2Opt Agent Design (OptiMUS-Style Pipeline)

## Status

Accepted

## Context

Users describe routing needs in natural language: "Seattle에서 Miami Beach까지,
트럭인데 폭풍 전에 도착해야 해." The system must translate this into structured
VRP constraints that OR-Tools can solve. The challenge is bridging unstructured
multilingual input (English, Korean, mixed) with a rigid JSON schema of 20+
constraint types (stops, time windows, vehicle specs, soft/hard constraints).

A naive end-to-end approach (LLM solves the routing problem directly) fails
because LLMs cannot execute combinatorial optimization. The LLM can parse
intent but cannot run OR-Tools, evaluate thousands of route permutations, or
guarantee constraint satisfaction.

## Decision

Implement an **OptiMUS-style 3-agent pipeline** (Stanford, 2024) adapted for
weather-aware VRP:

### Agent 1: Extraction (NL → JSON constraints)

- Few-shot prompting with 15 domain-specific examples (weather, hazmat, time)
- Function calling for structured JSON output
- Schema validation via `VrpConstraintValidator`
- **OPRO repair loop**: on validation failure, feed errors back to LLM with
  the previous output; max 3 attempts (DeepMind, ICLR 2024)
- Geocoding: resolved place names via Nominatim after extraction

### Agent 2: Formulation (JSON → OR-Tools model)

- Maps constraint JSON to OR-Tools VRP model parameters
- Integrates weather risk costs from `cost_model.py`
- Soft constraints become weighted penalty terms
- Hard constraints become solver bounds

### Agent 3: Interpretation (solver result → NL explanation)

- Translates solver output into natural language
- Includes segment-level risk breakdown
- Cites weather data and historical observations (RAG-grounded)

### Why not end-to-end LLM solving

| Concern | End-to-end LLM | Pipeline approach |
| --- | --- | --- |
| Combinatorial optimization | Cannot execute | OR-Tools handles |
| Constraint guarantee | Probabilistic | Deterministic solver |
| Debuggability | Opaque | Each stage inspectable |
| Repair | Full re-generation | Targeted schema repair |
| Latency | Single long call | Parallelizable stages |

### OPRO repair loop

When extraction output fails schema validation:

1. Collect validation errors (missing fields, type mismatches, enum violations)
2. Append previous output + errors to conversation
3. Prompt: "Fix the errors and output valid JSON matching the schema"
4. Re-validate; repeat up to 3 times
5. If still failing, raise `ExtractionFailedException` with error details

This pattern (from OPRO) treats the LLM as its own optimizer: previous
attempts become context for improvement. Empirically, 90%+ of failures
resolve within 1-2 repair attempts.

## Evaluation

- **50-scenario benchmark** (`nl2opt_scenarios.json`): covers simple routes,
  multi-stop, modifications, fleet planning, and weather-specific queries
- **Metrics**: constraint precision, recall, F1, geocoding accuracy, repair rate
- **Baselines**: (a) few-shot LLM, (b) zero-shot LLM, (c) keyword extraction
- **Target**: F1 > 0.85 on the full benchmark

## Consequences

- Extraction adds one LLM round-trip (~1-3s) before solving begins.
- Repair loop adds up to 3 additional calls in worst case.
- Schema changes require updating both the prompt and `VrpConstraintSchema`.
- Multilingual input works without translation; the LLM handles code-switching.
- The pipeline is testable at each stage independently.
