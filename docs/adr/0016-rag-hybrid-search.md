# ADR 0016: RAG with Hybrid Search for Route Explanations

## Status

Accepted

## Context

The LLM route explanation feature (`RouteInterpretationService`) generates
natural-language summaries of solver results and weather risk. Without
grounding data, the LLM can hallucinate historical events, invent risk
statistics, or produce plausible-sounding but factually incorrect
explanations. Users relying on these explanations for routing decisions
need confidence that the output reflects real observed data.

Retrieval-Augmented Generation (RAG) addresses this by retrieving relevant
historical observations before prompting the LLM, giving it concrete data
to cite rather than relying on parametric memory.

## Decision

Implement RAG using **hybrid search** over the `route_risk_observation`
table: pgvector cosine similarity + PostgreSQL full-text keyword search,
fused with Reciprocal Rank Fusion (RRF), followed by cross-encoder
reranking of the top candidates.

### Pipeline

```
Query
  |
  +---> pgvector ANN (HNSW, cosine) ---> top-k vector results
  |
  +---> tsvector keyword search -------> top-k keyword results
  |
  +---> RRF fusion (k=60) ------------> merged candidate set
  |
  +---> Cross-encoder reranking -------> top-n reranked results
  |
  +---> Inject into LLM prompt as context
```

### Why hybrid search

Pure vector search misses exact terms. A query for "I-95" may not embed
close to a document that mentions "Interstate 95" if the embedding model
has not seen that equivalence often. Pure keyword search misses semantic
similarity: "road was underwater" should match "flood inundation" even
though they share no tokens. Hybrid search covers both failure modes.

### Why RRF

Reciprocal Rank Fusion is simple, parameter-light (single constant k),
and effective. It does not require learned weights or score normalization
across heterogeneous retrieval signals. The k=60 default is well-studied
in the IR literature and works without tuning for this corpus size.

### Why cross-encoder reranking

Cosine similarity between query and document embeddings is a coarse
relevance signal. A cross-encoder (or the LLM itself in a reranking
prompt) reads the query and candidate together and produces a finer
relevance judgment. This catches cases where the embedding space
conflates topically similar but semantically distinct observations.

### Why pgvector over a dedicated vector DB

AtmosPath already runs PostgreSQL 16 with TimescaleDB. Adding pgvector
is an extension install, not a new service. At portfolio scale (~100k
observations, 384-dim embeddings), HNSW index build and query latency
are well within acceptable bounds. A dedicated vector database adds
operational complexity (another container, another connection pool,
another backup strategy) without proportional benefit at this scale.

## Alternatives Considered

| Option | Why not |
| --- | --- |
| **Pinecone** | Managed vector DB. Adds a cloud dependency and recurring cost. Data leaves the VPC. Overkill for ~100k vectors. |
| **Weaviate** | Self-hosted vector DB with hybrid search built in. More infrastructure to operate (separate container, separate API). Reasonable choice if vector count exceeds 10M. |
| **No RAG** | LLM generates explanations from parametric memory only. High hallucination risk for specific routes, dates, and risk scores. Unacceptable for a safety-adjacent product. |
| **Pure vector search** | Misses exact-match terms (route IDs, city names). Lower recall on queries with specific identifiers. |
| **Pure keyword search** | Misses semantic similarity. Cannot match paraphrased or conceptually related observations. |

## Consequences

- **Embedding generation adds write latency.** Each new observation
  requires an embedding API call (~50-100ms) before insert. Batched
  ingestion amortizes this; real-time single inserts pay the full cost.
- **HNSW index must be rebuilt on model change.** Switching embedding
  models (e.g., 384-dim to 768-dim) invalidates all stored vectors.
  A full re-embedding pass is required. The `embedding_model` column
  tracks which model produced each vector to support gradual migration.
- **RRF constant k=60 is a reasonable default** but may need tuning
  if the corpus grows significantly or query patterns shift.
- **Cross-encoder reranking adds latency** (~100-200ms for top-10
  candidates). Acceptable for the explanation use case, which is
  already LLM-latency-bound.
- **pgvector scales to ~1M vectors** on a single PostgreSQL instance
  with HNSW. Beyond that, evaluate a dedicated vector DB (see
  `production-scaling.md`).
- **Evaluation framework** (`RagEvaluationService`) provides recall@k,
  MRR, and NDCG metrics against a golden test set to track retrieval
  quality across model and index changes.
