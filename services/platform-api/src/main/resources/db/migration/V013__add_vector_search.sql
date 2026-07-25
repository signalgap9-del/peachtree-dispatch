-- V013: pgvector extension, embedding columns, and HNSW indexes
-- Enables semantic vector search over risk observations and alert events.

-- Enable pgvector (bundled in the timescale/timescaledb image)
CREATE EXTENSION IF NOT EXISTS vector;

-- Add embedding columns to searchable tables
ALTER TABLE route_risk_observation ADD COLUMN IF NOT EXISTS embedding vector(384);
ALTER TABLE alert_event ADD COLUMN IF NOT EXISTS embedding vector(384);

-- HNSW indexes for approximate nearest neighbor search
-- m=16, ef_construction=64: good balance of recall vs build time
CREATE INDEX IF NOT EXISTS idx_observation_embedding
    ON route_risk_observation USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

CREATE INDEX IF NOT EXISTS idx_alert_embedding
    ON alert_event USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Composite indexes for hybrid search (vector + SQL filters)
CREATE INDEX IF NOT EXISTS idx_observation_time_route
    ON route_risk_observation (saved_route_id, time DESC);

CREATE INDEX IF NOT EXISTS idx_alert_tenant_state
    ON alert_event (tenant_id, state, triggered_at DESC);

-- Embedding metadata table for model version tracking
CREATE TABLE IF NOT EXISTS embedding_metadata (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    table_name      TEXT NOT NULL,
    record_id       TEXT NOT NULL,
    embedding_model TEXT NOT NULL,
    embedding_version INT NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ DEFAULT now(),
    UNIQUE(table_name, record_id, embedding_model)
);
